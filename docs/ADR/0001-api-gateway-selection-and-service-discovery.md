# ADR 0001: Nginx as Edge API Gateway and Service Discovery Mechanics Under Scaling

## Status
**Accepted** — 2026-09-06

> **Note (2026-09-06):** The "Tier 2 — East-West (Service → Service)"
> decision below (Eureka + Spring Cloud LoadBalancer) is **superseded by
> [ADR 0002](0002-remove-eureka-config-server-k8s-ingress.md)**, which
> removes Eureka in favor of native platform discovery (k8s CoreDNS/
> kube-proxy, Compose embedded DNS). Tier 1 (Nginx edge routing) and its
> DNS-caching mitigation remain accurate for Compose/Swarm; ADR 0002
> replaces the k8s gateway with a native Ingress instead.

---

## Context
The **Hopr** URL Shortener system is designed as a distributed microservice architecture comprising:
- **`eureka-server`** (port 8761): Service registry & discovery (Spring Cloud Netflix Eureka).
- **`keygen-service`** (port 8081): High-performance unique key generation (Snowflake ID + Base62).
- **`shortener-service`** (port 8080): Ingests long URLs, invokes keygen, persists mappings in MongoDB Atlas, and caches hot records in Redis.
- **`resolver-service`** (port 8083): Resolves short keys, queries Redis/MongoDB, and performs HTTP redirects to original URLs.
- **6-node Redis Cluster** & **MongoDB Atlas** for distributed caching and persistence.

The system requires a unified Edge API Gateway exposed on public HTTP port `80` to:
1. Serve as a single entry point for client traffic and intelligently route requests (`/shorten` vs. `/{shortKey}`).
2. Protect downstream services via high-performance Rate Limiting against brute-force, DoS attacks, and URL spamming.
3. Terminate and handle CORS preflight (`OPTIONS`) requests for cross-origin frontend clients.
4. Distribute load when backend microservice instances (replicas) scale horizontally in a container cluster.

The current architecture employs **Nginx** as the Edge API Gateway rather than a JVM-based solution such as **Spring Cloud Gateway**. This ADR documents the rationale behind this decision and details the service discovery and load-balancing mechanics when instances scale.

---

## Core Questions Addressed

1. **Why use Nginx instead of Spring Cloud Gateway?**
2. **Does Nginx support Service Discovery and load balancing when scaling service instances?**

---

## Decision

1. **Deploy Nginx as the Edge API Gateway (North-South Traffic)**:
   - Positioned at the network perimeter receiving all inbound traffic on port `80`.
   - Responsible for reverse proxying, regex-based URL routing, native C-level rate limiting (`ngx_http_limit_req_module`), and CORS preflight handling.

2. **Implement a Two-Tier Hybrid Service Discovery & Load Balancing Model**:
   - **Tier 1 — North-South (Edge → Services)**: Powered by **Docker Swarm Virtual IP (VIP) & IPVS Routing Mesh**. Nginx remains intentionally decoupled from Eureka.
   - **Tier 2 — East-West (Service → Service)**: Powered by **Netflix Eureka Server + Spring Cloud LoadBalancer** (`@LoadBalanced WebClient`) for internal inter-service communication (e.g., `shortener-service` calling `keygen-service`).

---

## Deep-Dive Analysis

### 1. Architectural Comparison: Nginx vs. Spring Cloud Gateway

| Dimension | Nginx (Current Choice) | Spring Cloud Gateway (Alternative) |
| :--- | :--- | :--- |
| **Resource Footprint** | **Extremely Low (~15MB – 30MB RAM)**. Compiled C binary with lean memory management. | **High (~300MB – 1GB+ RAM)**. Requires a full JVM runtime, Netty reactor, and Spring ApplicationContext. |
| **Throughput & Latency** | Ultra-high throughput with minimal latency overhead; non-blocking event loop (`epoll`). Optimized for raw socket streaming and static proxying. | Good (built on Project Reactor / WebFlux), but subject to JVM Garbage Collection (GC) pauses and runtime overhead. |
| **Startup Time** | **< 0.1 second**; immediately ready upon container initialization. | **5 – 15 seconds** to bootstrap Spring context, initialize Netty, and fetch Eureka registry. |
| **Edge Protection & Rate Limiting** | Native C-level rate limiting via `limit_req_zone`. Dropped requests (`429 Too Many Requests`) are rejected directly at the TCP/socket layer without hitting app memory. | Requires custom Java/YAML GatewayFilters; distributed rate limiting necessitates an external Redis RateLimiter dependency. |
| **Decoupling & Maintenance** | **Completely independent of the Java ecosystem**. Upgrading Java runtimes (e.g., Java 21 → 25) or framework major versions (Spring Boot 3.5 → 4.1) does not impact or require rebuilding the gateway. | Tightly coupled to the Spring ecosystem. Must be upgraded, tested, and maintained alongside application dependencies. |
| **Service Discovery Integration** | Unaware of Eureka. Relies on container orchestrator DNS / VIP (Docker Swarm or Kubernetes). | **100% native Eureka integration**. Directly pulls live instance lists from the Eureka server registry. |

> **Conclusion**: For edge-layer responsibilities (rate limiting, CORS, static pattern routing), Nginx provides superior stability, significantly lower operational overhead, and minimal memory consumption compared to running an additional Spring Boot JVM service solely as a gateway.

---

### 2. Service Discovery Mechanics When Scaling Instances

Although **Nginx has no connection to Eureka**, **scaling instances remains fully automated** through Docker Swarm's network layer.

#### Traffic Flow Diagram

```
                                [ CLIENT / BROWSER ]
                                         │
                                         ▼ (Port 80)
                             ┌───────────────────────┐
                             │   Nginx API Gateway   │
                             │ (Rate Limit: 5 req/s) │
                             └───────────┬───────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
         (POST /shorten)                                 (GET /{shortKey})
                 │                                               │
                 ▼                                               ▼
     ┌────────────────────────┐                     ┌────────────────────────┐
     │ shortener-service VIP  │                     │  resolver-service VIP  │
     │  (Docker Swarm IPVS)   │                     │  (Docker Swarm IPVS)   │
     └───────────┬────────────┘                     └───────────┬────────────┘
        Round    │                                     Round    │
        Robin    ├──────────────┬──────────────┐       Robin    ├──────────────┬──────────────┐
                 ▼              ▼              ▼                ▼              ▼              ▼
            ┌─────────┐    ┌─────────┐    ┌─────────┐      ┌─────────┐    ┌─────────┐    ┌─────────┐
            │ Pod 1   │    │ Pod 2   │    │ Pod 3   │      │ Pod 1   │    │ Pod 2   │    │ Pod 3   │
            └────┬────┘    └─────────┘    └─────────┘      └─────────┘    └─────────┘    └─────────┘
                 │
                 │ (East-West: Eureka Client-side Load Balancing)
                 ▼ http://keygen-service/generate
            ┌────────────────────────┐
            │     keygen-service     │
            │  (Registered w/ Eureka)│
            └────────────────────────┘
```

#### Two-Tier Orchestration Details

1. **North-South (Nginx → Services) via Docker Swarm VIP**:
   - In `stack.yml`, Docker Swarm assigns each service a single, immutable **Virtual IP (VIP)** on the overlay network (`default`):
     ```nginx
     upstream shortener {
         server shortener-service:8080;
     }
     ```
   - When scaling replicas:
     ```bash
     docker service scale Hopr_shortener-service=5
     ```
   - Nginx always resolves the hostname `shortener-service` to that single Virtual IP.
   - **Linux IPVS (IP Virtual Server)** in the Linux kernel on Swarm nodes intercepts packets sent to the VIP and transparently balances them (Round-Robin) across all 5 active container instances. Nginx never needs to know the individual container IP addresses.

2. **East-West (Service → Service) via Netflix Eureka**:
   - Internal microservice calls bypass Nginx and Docker Swarm VIPs:
     - `keygen-service` registers its ephemeral container IP and port with `eureka-server`.
     - `shortener-service` uses a Spring WebClient annotated with `@LoadBalanced`.
     - Spring Cloud LoadBalancer fetches instances directly from the Eureka registry and handles client-side load balancing.

---

### 3. Known Caveats & Recommended Mitigations

#### Open-Source Nginx DNS Caching Gotcha
- **Default Behavior**: Standard open-source Nginx resolves upstream hostnames (`shortener-service`, `resolver-service`) **only once at process startup** and caches the IP indefinitely.
- **Risk**: If a Swarm service is removed and recreated such that its Virtual IP changes, Nginx may retain the stale IP, resulting in intermittent `502 Bad Gateway` errors until reloaded.
- **Mitigation**: To achieve zero-downtime VIP failover without full restarts, define the internal Docker DNS resolver (`127.0.0.11`) with a short TTL and route through dynamic variables:

```nginx
http {
    # Embedded Docker Engine / Swarm DNS server
    resolver 127.0.0.11 valid=10s ipv6=off;

    server {
        listen 80;

        location = /shorten {
            set $upstream_shortener shortener-service:8080;
            proxy_pass http://$upstream_shortener;
        }

        location ~ "^/[a-zA-Z0-9_-]{4,}$" {
            set $upstream_resolver resolver-service:8083;
            proxy_pass http://$upstream_resolver;
        }
    }
}
```

---

## Consequences

### Positive
- **Resource Optimization**: Frees up several hundred megabytes of RAM per host node that would otherwise be dedicated to a JVM-based gateway.
- **Resilient Edge Protection**: Rate limiting and CORS are enforced at the network perimeter; malicious or abusive traffic is dropped before entering the Spring application container.
- **Operational Simplicity**: Clean separation of concerns between infrastructure/edge routing (Nginx + Swarm) and domain service discovery (Eureka).
- **Runtime Independence**: Gateway uptime is isolated from Java framework and runtime upgrades.

### Trade-offs & Limitations
- Nginx does not inspect Eureka health checks directly; it relies on Docker Swarm's health status to remove unhealthy containers from the VIP pool.
- Advanced Spring-specific gateway filters (e.g., custom Spring Security Reactive authentication filters, Spring Cloud CircuitBreaker on the perimeter) cannot be written in Java; if complex domain-level edge logic is needed in the future, transitioning to Spring Cloud Gateway may be revisited.
