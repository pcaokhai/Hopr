# ADR 0002: Remove Eureka, Introduce Spring Cloud Config Server, Adopt Native Platform Discovery and k8s Ingress

## Status
**Accepted** — 2026-09-06

This ADR **partially supersedes [ADR 0001](0001-api-gateway-selection-and-service-discovery.md)** —
specifically its "Tier 2 — East-West (Service → Service)" decision, which
relied on Netflix Eureka + Spring Cloud LoadBalancer. ADR 0001's Tier 1
(Nginx/edge routing rationale) and its DNS-caching mitigation remain valid
for the Docker Compose deployment target; see the k8s-specific decision
below for how Kubernetes replaces that tier entirely.

---

## Context

Three related questions came up together while extending Hopr's deployment
options (Docker Compose, Docker Swarm `stack.yml`, and a new local
Kubernetes/`kind` + Helm target, see `k8s/README.md`):

1. **Centralized configuration**: every service currently hard-codes its
   own `application.yml` with `${ENV_VAR}` placeholders (ports, Mongo URI,
   Redis node list, Eureka URL, domain). As the number of deployment
   targets grew (Compose, Swarm, k8s), keeping these three copies of the
   same structural config in sync became error-prone (see the earlier
   `.env` vs `application.yml` naming mismatches documented in
   `k8s/README.md`'s "Environment Variables — Mapping from `.env`" section).

2. **Is Eureka still earning its keep?** ADR 0001 justified Eureka for
   *East-West* (service-to-service) discovery, reasoning that Nginx (edge)
   and Swarm VIP/IPVS (Compose networking) don't know about individual pod
   IPs. But:
   - **Kubernetes already provides this**: every k8s `Service` gets a
     stable ClusterIP + DNS name via CoreDNS, and `kube-proxy` load-balances
     across all matching pod endpoints automatically — the exact problem
     Eureka + Spring Cloud LoadBalancer was solving, just done by the
     platform instead of the JVM.
   - **Docker Compose already provides this too**: containers on the same
     user-defined network get automatic DNS resolution by service name via
     Compose's embedded DNS (`127.0.0.11`) — no VIP/IPVS layer is even
     needed for a single-replica-per-service local Compose setup like this
     one.
   - Auditing the actual code (`KeyGenClient.java`, `WebClientConfig.java`,
     `EurekaClientConfig.java` in both `shortener-service` and
     `keygen-service`) found only **one** real call site depending on
     Eureka-based discovery: `shortener-service` → `keygen-service` via
     `@LoadBalanced WebClient` calling the *unqualified* URL
     `http://keygen-service/generate` (no port — Eureka supplied it from
     the registered instance). `resolver-service` already has
     `register-with-eureka: false` / `fetch-registry: false` and has zero
     code coupling to Eureka. The Nginx gateway has never used Eureka at
     all (`nginx.conf`'s upstreams are plain Service/container names).
   - Keeping Eureka going forward would mean maintaining a discovery
     mechanism the platform already replaces, for exactly one call site.

3. **k8s gateway modernization**: the k8s deployment currently runs its own
   `nginx:latest` Deployment (`k8s/hopr-chart/templates/api-gateway.yaml`),
   copying `api-gateway/nginx.conf` verbatim via a ConfigMap. Kubernetes'
   native `Ingress` resource (backed by an Ingress Controller) exists to
   solve exactly this — path-based routing to backend Services — as a
   first-class, declarative k8s object instead of a hand-rolled nginx pod.

---

## Decision

### 1. Remove Eureka entirely (code + all three deploy targets)

- Delete the `eureka` Gradle module (`settings.gradle.kts`, root
  `build.gradle.kts`'s `bootApps` set).
- Remove `spring-cloud-starter-netflix-eureka-client` from the dependency
  block applied to *all* subprojects in root `build.gradle.kts` — it was
  added unconditionally to every module, including ones (like `common` and
  the future `config-server`) that never used it.
- `shortener-service`:
  - Delete `EurekaClientConfig.java` (Eureka health-check bean +
    `EurekaInstanceConfigBean` wiring — dead without a Eureka client).
  - Remove `@EnableDiscoveryClient` from `UrlShortenerApplication`.
  - `WebClientConfig`: remove `@LoadBalanced` — plain `WebClient.builder()`.
  - `KeyGenClient`: change the hard-coded logical URL
    `http://keygen-service/generate` to the literal, port-qualified
    `http://keygen-service:8081/generate` (port comes from a new
    `keygen.service.url` property, sourced from Config Server — see
    Decision 2). This works unchanged across Compose, Swarm, and k8s
    because `keygen-service` is *also* the literal container/Service DNS
    name in all three (this was already true before — Eureka's logical
    service ID and the platform's DNS name happened to be identical
    strings, which is what makes this swap safe).
- `keygen-service`: delete its `EurekaClientConfig.java`, remove
  `@EnableDiscoveryClient`.
- `resolver-service`: no code changes — it never depended on Eureka.
- Remove `eureka.client.*`, `eureka.instance.*`,
  `spring.cloud.loadbalancer.*`, and `spring.cloud.inetutils.*` from every
  `application.yml` and `application-test.yml`, along with the
  `spring.autoconfigure.exclude` Eureka entries and
  `spring.cloud.discovery.enabled=false` test overrides (no longer
  meaningful once the client dependency is gone).
- Remove the `eureka-server` service/Deployment from `docker-compose.yml`,
  `stack.yml`, and the k8s Helm chart (no `eureka.yaml` template).
- Remove `EUREKA_*` variables from `.env` / the k8s ConfigMap.

### 2. Introduce a Spring Cloud Config Server (native, file-backed)

- New `config-server` Gradle module, `spring-cloud-config-server`,
  `@EnableConfigServer`, serving YAML from
  `config-server/src/main/resources/config-repo/` via the **native**
  profile (`spring.cloud.config.server.native.search-locations=classpath:/config-repo`)
  — no separate Git repository. Chosen over a Git-backed repo because this
  is a single monorepo local/test setup; a native file backend avoids an
  unnecessary extra moving part.
- Each consuming service (`keygen-service`, `shortener-service`,
  `resolver-service`) keeps a minimal local `application.yml` — just
  `spring.application.name` plus a **profile-guarded**
  `spring.config.import`:
  ```yaml
  spring:
    application:
      name: shortener-service
  ---
  spring:
    config:
      activate:
        on-profile: "!test"
      import: "configserver:http://config-server:8888"
  ```
  The `!test` guard is required: without it, every `@SpringBootTest` in the
  existing test suites (none of which currently disable config import)
  would try to contact a real Config Server at test time.
- All previously-local structural config (ports, Mongo URI wiring, Redis
  node list, cache settings, the new `keygen.service.url`, springdoc paths)
  moves into per-service files in `config-repo/` (`keygen-service.yml`,
  `shortener-service.yml`, `resolver-service.yml`) plus one shared
  `application.yml` for cross-service defaults (Redis password/timeout/
  cache-type, springdoc config).
- **Secrets are unaffected.** Config Server ships YAML text like
  `mongo.uri: ${MONGO_URI}` to each client; the *client* JVM resolves
  `${MONGO_URI}` from its own container environment — exactly as today.
  `.env` / Compose `env_file` / k8s ConfigMap+Secret injection does not
  change; only the property *structure* moves to one place.
- Services connect to Config Server via a **static URL**
  (`http://config-server:8888`), the same pattern already used for
  Eureka's `defaultZone` — not via discovery — since Config Server itself
  is the thing that would otherwise need to be discovered before discovery
  works.
- **Startup-order resilience**: `spring.config.import=configserver:...`
  fails fast by default. Since nothing in this repo blocks container start
  on another container's readiness (no `depends_on: condition:
  service_healthy` anywhere), each consuming service's local bootstrap
  YAML enables `spring.cloud.config.fail-fast: true` with
  `spring.cloud.config.retry.*` (backoff, several attempts) so a service
  that starts before `config-server` is ready retries instead of
  crash-looping.
- `docker-compose.yml` gets a new `config-server` service (build + expose
  `8888:8888`, no `.env` needed — it holds no secrets of its own), added to
  the `depends_on` list of every consuming service. Same addition to
  `stack.yml`. The Helm chart gets a new `templates/config-server.yaml`
  (Deployment + ClusterIP Service); no new `values.yaml` entries are
  needed since the URL is a fixed service name, identical to how Eureka's
  URL was previously hard-coded.

### 3. Replace the k8s nginx pod with a native Ingress

- `k8s/hopr-chart/templates/api-gateway.yaml` (the hand-rolled `nginx`
  Deployment + Service + nginx.conf ConfigMap) is deleted.
- `k8s/deploy.sh` installs `ingress-nginx` (the standard `kind`-compatible
  manifest) before deploying the app chart. `kind-config.yaml` gains the
  `ingress-ready=true` node label the `kind`-flavored `ingress-nginx`
  manifest requires, alongside the existing `extraPortMappings`.
- A new `templates/ingress.yaml` replaces `nginx.conf`'s routing rules as
  native `Ingress` path rules:
  - `/shorten` (exact) → `shortener-service:8080`
  - a regex path matching short keys → `resolver-service:8083`
    (`nginx.ingress.kubernetes.io/use-regex: "true"`)
  - `/` (prefix, catch-all) → `shortener-service:8080`
  - Rate limiting (`nginx.ingress.kubernetes.io/limit-rps`) and CORS
    (`nginx.ingress.kubernetes.io/enable-cors` and related annotations)
    are re-expressed as Ingress annotations to preserve `nginx.conf`'s
    current behavior — `ingress-nginx` is itself Nginx under the hood, so
    this is the same reverse proxy, now managed as a native k8s resource
    instead of a hand-maintained Deployment.
- **Docker Compose is explicitly out of scope for this change** — Compose
  has no Ingress concept, so its `api-gateway` service keeps running plain
  `nginx.conf` unchanged. The only indirect effect on it is that the
  gateway now only ever needs to know about 2 upstreams (`shortener`,
  `resolver`) instead of 3, because Eureka is gone — but `nginx.conf`
  never routed through Eureka in the first place, so no change is required
  there.
- `kind` does **not** ship an Ingress Controller by default — installing
  `ingress-nginx` is an explicit, acknowledged addition to `deploy.sh`,
  not something already present. This trade-off (one more thing to
  install vs. no longer hand-maintaining a gateway Deployment/ConfigMap)
  was discussed and accepted.

---

## Consequences

### Positive
- One less service to build, deploy, and keep registered/healthy across
  three deployment targets (Compose, Swarm, k8s).
- Configuration structure for all services lives in one place
  (`config-server/src/main/resources/config-repo/`), fixing the drift
  between `.env` naming and each service's actual `application.yml`
  property names documented in `k8s/README.md`.
- k8s gateway routing becomes a declarative `Ingress` resource, reviewable
  and diffable like any other k8s object, instead of an nginx pod running
  a copy-pasted `nginx.conf`.
- Removes a large, cross-cutting dependency (`spring-cloud-starter-netflix-eureka-client`)
  that was applied to every Gradle subproject regardless of whether it was
  used.

### Trade-offs & Limitations
- Config Server is now a **hard dependency for every service's startup** —
  if it's down and retries exhaust, no service boots. This risk didn't
  exist when config was purely local+env-var driven. Mitigated by
  `fail-fast` + `retry`, but not eliminated.
- `keygen.service.url` hard-codes `keygen-service:8081` as a literal
  hostname:port. This is safe only because the Eureka logical service ID,
  the Compose/Swarm service key, and the k8s Service name were already
  required to be identical strings — a coincidence this design now
  depends on structurally rather than incidentally.
- `ingress-nginx` in `kind` requires cluster-level setup
  (`ingress-ready=true` node label, specific manifest) that a plain
  `kubectl apply -f` Deployment didn't need. Local `kind` clusters not
  created via `k8s/kind-config.yaml` (or created before this change) need
  to be recreated.
- Docker Swarm (`stack.yml`) and Docker Compose keep Nginx as their
  gateway — the routing logic now exists in **two** places (`nginx.conf`
  for Compose/Swarm, `templates/ingress.yaml` for k8s) that must be kept
  behaviorally in sync by hand if routing rules change in the future.
- ADR 0001's Tier 2 (East-West via Eureka) no longer applies anywhere;
  ADR 0001's Tier 1 (Nginx edge + Swarm VIP/IPVS) and its DNS-caching
  mitigation remain accurate for Compose/Swarm only, not for k8s (which
  uses CoreDNS + kube-proxy + Ingress instead).

---

## Alternatives Considered

| Alternative | Why not chosen |
|---|---|
| Keep Eureka, add Config Server on top | Would mean maintaining a discovery mechanism the platform (k8s CoreDNS/kube-proxy, Compose embedded DNS) already provides, for a single call site (`shortener-service` → `keygen-service`). |
| Git-backed Config Server (real Git repo) | More "correct" for a production multi-repo setup, but adds an unnecessary moving part for this single-monorepo local/test project. Native file backend was sufficient. |
| Config Server discovered via Eureka (`spring.cloud.config.discovery.enabled`) | Moot once Eureka is removed; would also have introduced a bootstrap chicken-and-egg problem (needing discovery to find the thing that provides config) even if Eureka had been kept. |
| Keep the nginx pod in k8s, skip Ingress | Simpler (no `ingress-nginx` install step), but keeps a hand-rolled Deployment doing exactly what a native k8s primitive is designed for; rejected in favor of using the platform's own mechanism, matching the same reasoning applied to removing Eureka. |
