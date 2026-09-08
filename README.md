# Hopr — High-Performance Distributed URL Shortener & Resolver

[![Java](https://img.shields.io/badge/Java-25-orange.svg?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.2%20(Oakwood)-blue.svg?style=flat-square)](https://spring.io/projects/spring-cloud)
[![Gradle](https://img.shields.io/badge/Gradle-9.4.1-02303A.svg?style=flat-square&logo=gradle)](https://gradle.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose%20%7C%20Swarm-2496ED.svg?style=flat-square&logo=docker)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Helm%20%7C%20kind-326CE5.svg?style=flat-square&logo=kubernetes)](https://kubernetes.io/)
[![Redis](https://img.shields.io/badge/Redis-6--Node%20Cluster-DC382D.svg?style=flat-square&logo=redis)](https://redis.io/)
[![MongoDB](https://img.shields.io/badge/MongoDB-7.0-47A248.svg?style=flat-square&logo=mongodb)](https://www.mongodb.com/)
[![JaCoCo Coverage](https://img.shields.io/badge/Coverage-%E2%89%A5%2085%25-green.svg?style=flat-square&logo=codecov)](https://www.eclemma.org/jacoco/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)

**Hopr** is a cloud-native, production-grade distributed URL shortening and resolution platform engineered for ultra-high throughput, sub-millisecond redirect latency, and horizontal scalability.

Designed with clean architecture and domain-driven principles, Hopr eliminates common database bottlenecks using **distributed 64-bit Snowflake ID generation**, **Base62 URL-safe encoding**, a **6-node Redis Cluster** multi-tier caching layer, and an **Nginx Edge API Gateway** with native C-level rate limiting.

---

## Table of Contents

- [Architectural Highlights](#architectural-highlights)
- [System Architecture](#system-architecture)
  - [High-Level Topology](#high-level-topology)
  - [Request Flow: Shorten & Resolve](#request-flow-shorten--resolve)
- [Microservices Overview](#microservices-overview)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Local Setup via Docker Compose](#local-setup-via-docker-compose)
  - [Kubernetes & Helm Deployment](#kubernetes--helm-deployment)
- [API Reference & Testing Guide](#api-reference--testing-guide)
  - [1. Shorten a URL (Auto-Generated Key)](#1-shorten-a-url-auto-generated-key)
  - [2. Shorten a URL with Custom Alias](#2-shorten-a-url-with-custom-alias)
  - [3. Resolve & Redirect (GET /{shortKey})](#3-resolve--redirect-get-shortkey)
  - [4. Verify Edge Rate Limiting](#4-verify-edge-rate-limiting)
  - [5. Interactive Swagger / OpenAPI UI](#5-interactive-swagger--openapi-ui)
- [Frontend](#frontend)
- [Quality Assurance & CI](#quality-assurance--ci)
- [Architecture Decision Records (ADRs)](#architecture-decision-records-adrs)
- [Project Directory Layout](#project-directory-layout)
- [Configuration Reference](#configuration-reference)
- [License](#license)

---

## Architectural Highlights

- **Decentralized, Collision-Free Key Generation**: Uses a 64-bit Twitter Snowflake algorithm (timestamp + worker ID + sequence) coupled with Base62 encoding. Generates 7-character URL-safe slugs without database auto-increment locks or central coordination overhead.
- **Sub-Millisecond Read Latency**: Read operations bypass database lookups via an in-memory Caffeine local cache backed by a distributed **6-node Redis Cluster** (3 masters + 3 replicas). MongoDB Atlas / Mongo 7.0 acts as durable cold storage.
- **Lightweight Edge API Gateway**: Powered by Nginx on port `80`, handling North-South routing, CORS preflight (`OPTIONS`), and socket-level token bucket rate limiting (5 req/s, burst 10) with negligible memory footprint (~20MB RAM) and zero GC pauses.
- **Cloud-Native Service Discovery**: Eliminates heavyweight JVM service registries (Netflix Eureka) in favor of container platform discovery (Docker Compose internal DNS, Docker Swarm IPVS VIPs, and Kubernetes CoreDNS/kube-proxy).
- **Centralized Spring Cloud Config Server**: Native repository-backed configuration server delivering environment-agnostic properties to all downstream microservices at startup.
- **Strict Quality Enforcement**: Enforced build-time JaCoCo verification rule requiring a minimum of **85% line coverage** across modules.
- **Modern Java & Spring Ecosystem**: Upgraded to **Java 25** (OpenJDK Temurin), **Spring Boot 4.1.1**, **Spring Cloud 2025.1.2 (Oakwood)**, and **Gradle 9.4.1 (Kotlin DSL)**.

---

## System Architecture

### High-Level Topology

```mermaid
flowchart TD
    Client(["Client / Browser"]) -->|HTTP Port 80| Gateway["Nginx API Gateway\n(Rate Limit: 5 req/s, Burst: 10)"]

    subgraph "Edge & Routing Layer"
        Gateway -->|POST /shorten| Shortener["shortener-service\n(:8080)"]
        Gateway -->|"GET /{shortKey}"| Resolver["resolver-service\n(:8083)"]
    end

    subgraph "Core Microservices"
        Shortener -->|HTTP GET /generate| KeyGen["keygen-service\n(:8081)"]
        ConfigServer["config-server\n(:8888)"] -.->|Bootstrap Config| Shortener
        ConfigServer -.->|Bootstrap Config| Resolver
        ConfigServer -.->|Bootstrap Config| KeyGen
    end

    subgraph "Data & Distributed Caching"
        Shortener -->|Write Mapping & Cache Prime| RedisCluster[("Redis Cluster (6 Nodes)\n3 Masters + 3 Replicas")]
        Shortener -->|Persist URL Record| MongoDB[("MongoDB 7.0\nPersistent Storage")]
        Resolver -->|1. Fast Cache Read| RedisCluster
        Resolver -.->|2. Cache-Miss Fallback| MongoDB
    end
```

### Request Flow: Shorten & Resolve

```mermaid
sequenceDiagram
    autonumber
    actor User as Client
    participant GW as Nginx Gateway (:80)
    participant SS as shortener-service (:8080)
    participant KS as keygen-service (:8081)
    participant RS as resolver-service (:8083)
    participant RC as Redis Cluster
    participant DB as MongoDB

    Note over User, GW: Flow 1: Create Short URL
    User->>GW: POST /shorten {"longUrl": "https://..."}
    GW->>SS: Forward request
    alt Custom Alias provided
        SS->>DB: Check alias availability
    else Auto-generate slug
        SS->>KS: GET /generate
        KS-->>SS: Return Base62 Key (e.g., "WuMBdp2")
    end
    SS->>DB: Persist UrlMapping
    SS->>RC: Prime cache (key -> longUrl)
    SS-->>GW: Return {"shortUrl": "http://hopr.localhost/WuMBdp2"}
    GW-->>User: HTTP 200 OK

    Note over User, GW: Flow 2: Resolve & Redirect
    User->>GW: GET /WuMBdp2
    GW->>RS: Forward request
    RS->>RC: Query key in Redis Cluster
    alt Cache Hit
        RC-->>RS: Return longUrl
    else Cache Miss
        RS->>DB: Query MongoDB by key
        DB-->>RS: Return UrlMapping
        RS->>RC: Repopulate cache
    end
    RS-->>GW: HTTP 307 Temporary Redirect (Location: longUrl)
    GW-->>User: HTTP 307 Redirect to Original Destination
```

---

## Microservices Overview

| Service | Technology | Port | Description |
| :--- | :--- | :--- | :--- |
| **`api-gateway`** | Nginx | `80` | Edge reverse proxy, CORS preflight handler, and C-level rate limiter (5 req/s, burst 10). |
| **`shortener-service`** | Spring Boot 4 / Java 25 | `8080` | URL shortening engine, alias validation, Redis cache priming, and MongoDB persistence. |
| **`resolver-service`** | Spring Boot 4 / Java 25 | `8083` | High-performance resolution engine returning `HTTP 307 Temporary Redirect` via Redis Cluster & MongoDB. |
| **`keygen-service`** | Spring Boot 4 / Java 25 | `8081` | Dedicated worker generating 64-bit Snowflake IDs encoded into Base62 URL slugs. |
| **`config-server`** | Spring Cloud Config | `8888` | Centralized external configuration repository for all microservices. |
| **`common`** | Java Library (JAR) | — | Shared domain entities (`UrlMapping`), DTOs, and exception models. |
| **`mongodb`** | MongoDB 7.0 | `27017` | Persistent document storage for URL mappings and metadata. |
| **`redis-cluster`** | Redis 7.x (6 Nodes) | `7001-7006` | Distributed, sharded cache layer (3 master nodes, 3 replica nodes). |

---

## Technology Stack

| Layer | Component | Details |
| :--- | :--- | :--- |
| **Runtime & Language** | Java | **OpenJDK 25** (via Eclipse Temurin toolchain) |
| **Framework** | Spring Ecosystem | **Spring Boot 4.1.1**, **Spring Cloud 2025.1.2 (Oakwood)** |
| **Build & Tooling** | Gradle | **Gradle 9.4.1** (Kotlin DSL multi-module build) |
| **Edge Gateway** | Nginx | **Nginx Alpine**, non-blocking event-loop (`epoll`), `limit_req_zone` |
| **Distributed Caching** | Redis | **6-node Redis Cluster** (sharded, master-replica replication) |
| **Local In-Memory Cache** | Caffeine | In-process cache for ultra-hot path resolution |
| **Persistence** | MongoDB | **MongoDB 7.0**, indexing on `alias` / `_id` |
| **Key Generation Algorithm** | Snowflake + Base62 | 64-bit timestamp + worker ID + sequence with Base62 character mapping |
| **API Documentation** | OpenAPI 3 | **springdoc-openapi 3.1.0** (Swagger UI on `/swagger-ui.html`) |
| **Code Coverage** | JaCoCo | Enforced build verification (Bundle line coverage $\ge 85\%$) |
| **Orchestration** | Docker / Kubernetes | Docker Compose V2, Docker Swarm (`stack.yml`), Kubernetes Helm Chart (`kind`) |

---

## Getting Started

### Prerequisites

- **Java 25** (or compatible JDK) installed locally.
- **Docker & Docker Compose** (Docker Desktop on macOS/Windows, or Docker Engine V2 on Linux).
- **cURL** or any REST API client (Postman, Bruno, HTTPie).
- *(Optional for k8s)*: `kind`, `kubectl`, and `helm`.

---

### Local Setup via Docker Compose

The simplest way to run the entire distributed environment locally:

#### Step 1: Clone and Build Application JARs

Dockerfiles copy compiled JARs from each service's `build/libs/`. Compile and package all services:

```bash
git clone https://github.com/pcaokhai/Hopr.git
cd Hopr

# Build executable JARs (skip unit tests for fast build)
./gradlew bootJar -x test
```

#### Step 2: Launch the Infrastructure & Microservices

Run Docker Compose in detached mode:

```bash
docker compose up -d --build
```

This command will:
1. Initialize the **MongoDB** instance and data volume.
2. Spin up **6 Redis nodes** and execute the one-shot `redis-cluster-init` container to form the cluster.
3. Start the **Config Server** and wait for it to be ready.
4. Launch **`keygen-service`**, **`shortener-service`**, and **`resolver-service`**.
5. Launch the **`api-gateway`** reverse proxy on port `80`.

#### Step 3: Verify Container Health

Check that all containers are healthy and running:

```bash
docker compose ps
```

*Note: `hopr-redis-cluster-init` with status `Exited (0)` is expected after successful cluster configuration.*

---

### Kubernetes & Helm Deployment

Hopr includes a first-party Helm chart and automated scripts to launch on a local **`kind`** (Kubernetes-in-Docker) cluster with native **`ingress-nginx`**:

```bash
cd k8s

# Build Docker images and load them directly into the kind cluster
./build-and-load.sh

# Spin up kind cluster, deploy Redis cluster via Helm, and deploy hopr-chart
./deploy.sh
```

For in-depth Kubernetes documentation, values overrides, and cluster architecture, refer to [`k8s/README.md`](k8s/README.md).

---

## API Reference & Testing Guide

All client requests enter through the Nginx Edge Gateway at `http://hopr.localhost/`.

### 1. Shorten a URL (Auto-Generated Key)

> ⚠️ **Notice**: Request payload must contain the key `longUrl`.

```bash
curl -v -X POST http://hopr.localhost/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://github.com/pcaokhai/Hopr"}'
```

**Response (HTTP 200 OK):**
```json
{
  "shortUrl": "http://hopr.localhost/8zK1x9P"
}
```

---

### 2. Shorten a URL with Custom Alias

```bash
curl -v -X POST http://hopr.localhost/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "longUrl": "https://spring.io",
    "alias": "spring-home"
  }'
```

**Response (HTTP 200 OK):**
```json
{
  "shortUrl": "http://hopr.localhost/spring-home"
}
```

*If the alias already exists, the service responds with `HTTP 409 Conflict`.*

---

### 3. Resolve & Redirect (GET /{shortKey})

Use the generated key or alias:

```bash
curl -v http://hopr.localhost/spring-home
```

**Response:**
```http
< HTTP/1.1 307 Temporary Redirect
< Server: nginx
< Location: https://spring.io
< Content-Length: 0
```

*Or paste `http://hopr.localhost/spring-home` directly into your web browser to verify automatic redirection.*

---

### 4. Verify Edge Rate Limiting

The API Gateway enforces rate limiting of **5 requests/second with a burst of 10**. Test the threshold using a bash loop:

```bash
for i in {1..15}; do
  curl -s -o /dev/null -w "Request $i: HTTP %{http_code}\n" \
    -X POST http://hopr.localhost/shorten \
    -H "Content-Type: application/json" \
    -d '{"longUrl": "https://example.com"}'
done
```

**Expected Result:** First 10–11 requests succeed (`200 OK`), after which subsequent requests are immediately rejected by Nginx with `429 Too Many Requests`.

---

### 5. Interactive Swagger / OpenAPI UI

When running locally, explore and test individual microservice APIs via Swagger UI:

- **Shortener Service Swagger**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Resolver Service Swagger**: [http://localhost:8083/swagger-ui.html](http://localhost:8083/swagger-ui.html)

---

## Frontend

A Next.js + TypeScript + Tailwind + shadcn/ui frontend lives in `frontend/`.
It wires the landing-page shorten form to the real `/shorten` API; the
dashboard and analytics screens use mock data (no list/analytics/auth
endpoint exists yet). See `frontend/README.md` for how to run it and what's
real vs. mocked.

---

## Quality Assurance & CI

Hopr adheres to strict testing and code coverage standards.

### Running Test Suites

Execute all unit and integration tests across the multi-project workspace:

```bash
./gradlew test
```

### JaCoCo Code Coverage Verification

Build-time validation ensures that business logic meets the **85% line coverage** requirement:

```bash
# Verify coverage thresholds
./gradlew jacocoTestCoverageVerification

# Generate unified HTML coverage reports
./gradlew jacocoTestReport
```

HTML reports will be generated in `*/build/reports/jacoco/test/html/index.html` for each service.

### GitHub Actions CI

Automated CI workflows (`.github/workflows/gradle-build-main.yml`) run on every push and pull request against the `main` branch, compiling with JDK 25 and executing test verification.

---

## Architecture Decision Records (ADRs)

Key architectural decisions are documented to preserve design rationale:

- **[ADR 0001: Nginx as Edge API Gateway and Service Discovery Mechanics Under Scaling](docs/ADR/0001-api-gateway-selection-and-service-discovery.md)**
  - *Rationale*: Chooses Nginx over JVM-based Spring Cloud Gateway to achieve ultra-low memory footprint (~20MB vs ~500MB), zero GC pause latency, and native socket-level rate limiting.
- **[ADR 0002: Removal of Eureka, Adoption of Spring Cloud Config Server & Native Platform Discovery](docs/ADR/0002-remove-eureka-config-server-k8s-ingress.md)**
  - *Rationale*: Eliminates Netflix Eureka in favor of native platform discovery (Docker Compose internal DNS, Swarm VIPs, Kubernetes CoreDNS/kube-proxy), introduces Spring Cloud Config Server for externalized configuration, and leverages native Kubernetes Ingress.

---

## Project Directory Layout

```
Hopr/
├── .github/workflows/         # GitHub Actions CI pipelines (Java 25)
├── api-gateway/               # Nginx configuration (routing, rate limit, CORS)
│   └── nginx.conf
├── common/                    # Shared library (domain models, DTOs)
├── config-server/             # Spring Cloud Config Server (native repo)
│   └── src/main/resources/config-repo/  # Centralized YAML configurations
├── keygen-service/            # Snowflake ID + Base62 key generator service
├── shortener-service/         # URL shortening & persistence service
├── resolver-service/          # Fast resolution & HTTP 307 redirect service
├── docs/                      # Architectural Decision Records (ADR) & docs
│   └── ADR/
├── k8s/                       # Kubernetes manifests, kind config & Helm chart
│   ├── hopr-chart/            # First-party Helm chart
│   ├── deploy.sh              # Local k8s automated deployment script
│   └── README.md
├── docker-compose.yml         # Local multi-container Docker Compose definition
├── stack.yml                  # Production-ready Docker Swarm stack
├── build.gradle.kts           # Multi-project root Gradle build (Kotlin DSL)
├── settings.gradle.kts        # Gradle module declarations
├── .env                       # Local environment variables
└── README.md
```

---

## Configuration Reference

Key variables defined in `.env`:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `MONGO_URI` | `mongodb://root:password@mongodb:27017/Hopr?authSource=admin` | MongoDB connection string |
| `REDIS_NODE_1` ... `REDIS_NODE_6` | `redis-node-1:6379` ... `redis-node-6:6379` | Hostnames and ports for the 6 Redis Cluster nodes |
| `SHORTENER_DOMAIN` | `http://hopr.localhost/` | Base domain prepended to generated short URLs |
| `CONFIG_SERVER_URL` | `http://config-server:8888` | Upstream Config Server endpoint for service bootstrap |
| `KEYGEN_SERVICE_URL` | `http://keygen-service:8081/generate` | Internal endpoint for Snowflake slug generation |

---

## Teardown & Clean Reset

- **Stop all services (preserve database and cache data):**
  ```bash
  docker compose down
  ```

- **Complete wipe and fresh restart (delete volumes and cached state):**
  ```bash
  docker compose down -v
  rm -rf ./data/mongodb/* ./data/redis-*/*
  ```

---

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
