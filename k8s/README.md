# Hopr — Local Kubernetes Deployment

This directory contains a **local-only** Kubernetes deployment of the Hopr
stack, built for manual testing on `kind`. It does not replace or modify
`docker-compose.yml` or `.env` at the repo root — those remain
untouched and independently usable.

## Prerequisites

- `kind`, `kubectl`, `helm`, `docker` — all installed and on `PATH`
- Docker daemon running
- Repo's Gradle wrapper working (`./gradlew`) — used to build the app jars

## Architecture

```
kind cluster "hopr"
 └─ namespace: hopr
     ├─ Helm release "hopr-redis"  (bitnami/redis-cluster, 6 nodes)
     └─ Helm release "hopr"        (this repo's own chart: k8s/hopr-chart)
         ├─ StatefulSet/Service: hopr-scylladb       (scylladb/scylla:6.2, 3 nodes)
         ├─ Deployment/Service: config-server         (port 8888)
         ├─ Deployment/Service: keygen-service        (port 8081)
         ├─ Deployment/Service: shortener-service     (port 8080)
         ├─ Deployment/Service: resolver-service       (port 8083)
         └─ Deployment/Service: frontend               (port 3000)
     └─ ingress-nginx (installed separately via upstream manifest)
         ├─ Ingress: hopr-ingress — /, /dashboard and /api to the frontend,
         │           /shorten to shortener-service, short keys to
         │           resolver-service, everything else to shortener-service
         └─ Ingress: hopr-static-ingress — /_next/ and root-level static assets
                     to the frontend, outside the rate limit
```

App images (`hopr/config-server`, `hopr/keygen-service`,
`hopr/shortener-service`, `hopr/resolver-service`, `hopr/frontend`) are built locally from the
existing Dockerfiles and loaded straight into the `kind` node with `kind load
docker-image` — `imagePullPolicy: Never` everywhere, no image registry
involved. The gateway is no longer a hand-rolled nginx pod in the app chart —
`deploy.sh` installs the upstream `ingress-nginx` controller into the cluster,
and `k8s/hopr-chart/templates/ingress.yaml` defines a native Kubernetes
`Ingress` resource that the controller reads to route traffic to
`shortener-service`, `resolver-service` and the `frontend`.

## File Layout

```
k8s/
  kind-config.yaml          kind cluster definition (1 node, port mapping)
  build-and-load.sh         builds jars + docker images, loads them into kind
  deploy.sh                 full deploy: cluster, Redis, dev API key + TLS cert,
                            app chart, rollout wait
  hopr-chart/                Helm chart for ScyllaDB + the 4 app services + frontend + Ingress
    Chart.yaml
    values.yaml              all tunable values (ports, image tag, scylla/redis config)
    templates/
      configmap.yaml          non-secret env vars (Redis nodes, ports...)
      secret.yaml             REDIS_PASSWORD (hopr-secret) + SHORTEN_API_KEY
                              (hopr-frontend-secret, frontend only)
      tls-secret.yaml         hopr-tls: the certificate/key the Ingresses terminate
                              TLS with; required, supplied via --set-file
      scylladb.yaml           ScyllaDB StatefulSet + headless Service
      config-server.yaml      Config Server Deployment + Service (port 8888)
      keygen-service.yaml     keygen-service Deployment + Service
      shortener-service.yaml  shortener-service Deployment + Service
      resolver-service.yaml   resolver-service Deployment + Service
      frontend.yaml           Next.js frontend Deployment + Service (port 3000)
      ingress.yaml            Ingress resource routing to shortener-service /
                              resolver-service / frontend via the ingress-nginx
                              controller
      ingress-static.yaml     second Ingress for the frontend's static assets,
                              exempt from the write-path rate limit
```

## Why Helm for the app layer, and why ScyllaDB isn't a Bitnami chart

- **Redis Cluster** uses the `bitnami/redis-cluster` chart directly — it
  matches the existing docker-compose topology (6 nodes) and its image is
  multi-arch (works on both Apple Silicon and Intel/amd64 hosts).
- **ScyllaDB** is a plain `StatefulSet` in this chart rather than the Scylla
  Operator: the operator brings its own CRDs and controller, which is more
  machinery than a throwaway kind cluster needs. Each node owns a slice of
  the token ring and its own volume, hence a StatefulSet plus a headless
  Service for stable pod DNS (`hopr-scylladb-0..2.hopr-scylladb`), which is
  also what the services use as `SCYLLA_CONTACT_POINTS`.
- The **app services** (config-server, keygen, shortener, resolver) have no
  third-party chart to reuse — they're this project's own code — so they're
  packaged as a small first-party chart (`k8s/hopr-chart`) instead of loose
  `kubectl apply -f` manifests, for versioning and easier `values.yaml`
  overrides (port, image tag, Scylla/Redis config). The gateway itself is a
  native Kubernetes `Ingress` resource (`templates/ingress.yaml`), routed by
  the upstream `ingress-nginx` controller that `deploy.sh` installs
  separately — not an app-chart pod.

Moving to the Scylla Operator later is a contained change — the app's
ConfigMap already isolates the connection details (`SCYLLA_CONTACT_POINTS`,
`SCYLLA_KEYSPACE`, `SCYLLA_DATACENTER`).

## Environment Variables — Mapping from `.env`

The k8s ConfigMap/Secret use the **exact env var names each service's
`application.yml` expects**, and so do the Compose templates at the repo
root (`.env.example` / `.env.secrets.example`) — the two sides no longer
differ in naming, so a variable added to one can be copied verbatim to the
other.

Non-secret values (Redis node list, Scylla contact points/keyspace/datacenter,
ports, domain) live in `hopr-config` (a ConfigMap); credentials
(`REDIS_PASSWORD`) live in `hopr-secret`, while the frontend's plaintext
`SHORTEN_API_KEY` lives alone in `hopr-frontend-secret` so the backend pods,
which consume `hopr-secret` wholesale, never carry a usable write key. Both are rendered from
`k8s/hopr-chart/values.yaml` by the chart's `configmap.yaml` /
`secret.yaml` templates — edit `values.yaml`, not the templates, to change
a value.

`keygen-service`, `shortener-service`, and `resolver-service` all also pull
their Spring config from the **Config Server** (`config-server:8888`) at
startup — the service-specific YAML files under
`config-server/src/main/resources/config-repo/` (`keygen-service.yml`,
`shortener-service.yml`, `resolver-service.yml`, plus shared
`application.yml`) are served over HTTP and layered on top of each service's
own `application.yml`. The Config Server itself needs **no** app-level env
vars and has **no** Spring Boot Actuator dependency, so its readiness/
liveness probes use `tcpSocket` on port 8888 instead of an HTTP health
check.

## Networking Notes

- Service-to-service calls are **direct HTTP calls to k8s Service DNS
  names**, not registry-resolved logical names — e.g. `shortener-service`
  calls `keygen-service` at `http://keygen-service:8081` (a plain in-cluster
  `ClusterIP` Service). There is no discovery/registry layer in the loop, so
  there's no registry-fetch delay to wait out after a rollout.
- The Ingress (`hopr-ingress`) reaches `resolver-service` and
  `shortener-service` directly by their k8s Service DNS names
  (`resolver-service:8083`, `shortener-service:8080`) as well — the
  `ingress-nginx` controller is just another client of those Services.
- **Redis** node hostnames in the ConfigMap
  (`hopr-redis-redis-cluster-N.hopr-redis-redis-cluster-headless:6379`)
  are the Bitnami chart's StatefulSet pod DNS names. If the `hopr-redis`
  Helm release name ever changes, `values.yaml`'s `redis.releaseName` must
  be updated to match — the chart template generates the 6 node hostnames
  from that value.
- All four app services (config-server, keygen, shortener, resolver) must be
  reachable and, for the three non-config-server services, have
  successfully fetched their config from `config-server:8888` before they
  finish starting — the Config Server is a hard startup dependency now, not
  an optional registry.

## How to Run

### First-time / full deploy

```bash
./k8s/deploy.sh
```

This will, in order:
1. Create the `kind` cluster `hopr` if it doesn't already exist (an existing cluster is
   reused, but one created before the HTTPS host port moved to node port 8443 is rejected
   with the `kind delete cluster --name hopr` command to fix it — port mappings cannot be
   changed after creation)
   (`k8s/kind-config.yaml` maps the node's container ports 80 → host port
   **8888** and 8443 → **8443**, and labels the node `ingress-ready=true` for
   `ingress-nginx`).
2. Create the `hopr` namespace, and mint a local development API key for
   `POST /shorten` into the gitignored `k8s/.dev-api-key` if it does not exist
   yet, plus a self-signed TLS certificate into `k8s/.dev-tls.crt`/`.dev-tls.key`
   (both reused on re-runs, so a redeploy never invalidates the key or cert
   already in the cluster — see "TLS" below). Both Helm invocations below get them via
   `--set shortenApiKey=<key> --set config.shortenerApiKeyHashes=<sha256>`.
   The chart's own defaults stay empty on purpose — `shortener-service`
   refuses to start on an empty hash list, so nothing deploys with a key
   published in this repo. For anything beyond local development, pass your
   own key and its digest on those two `--set` flags instead:
   `KEY=$(openssl rand -hex 32); printf %s "$KEY" | shasum -a 256`.
3. `helm upgrade --install hopr-redis bitnami/redis-cluster` (6 nodes,
   `bitnamilegacy/*` images — see note below).
4. Install the upstream `ingress-nginx` controller and wait for it to
   become ready.
5. Build all 4 app jars with Gradle, build their Docker images plus the
   frontend image, and `kind load docker-image` them into the cluster
   (`k8s/build-and-load.sh`).
6. On a fresh install only (no existing `hopr` Helm release):
   `helm upgrade --install hopr ./k8s/hopr-chart --set urlServices.enabled=false`
   — deploys ScyllaDB, `config-server`, `keygen-service`, and the `Ingress`
   resource, but not yet `shortener-service`/`resolver-service`. On a re-run
   this pass is skipped so the running URL services are not torn down.
7. Wait for the ScyllaDB StatefulSet to become ready, then apply the Flyway
   schema through a `kubectl port-forward` to `hopr-scylladb-0`
   (`./gradlew :db-migration:migrateScylla -Pscylla.contactPoint=127.0.0.1:9042`).
   `shortener-service` and `resolver-service` open a session against the `hopr`
   keyspace at boot, which is why they are not created until this has run.
8. `helm upgrade --install hopr ./k8s/hopr-chart` — adds (or upgrades) the two URL services.
9. Wait for every Deployment's rollout to finish.

The script uses `set -euo pipefail` and is **idempotent** — re-running it on
an already-deployed cluster is safe (`helm upgrade --install` and
`kubectl`/Helm apply semantics are all no-ops when nothing changed).

### Rebuilding just the app images (after code changes)

```bash
./k8s/build-and-load.sh
kubectl rollout restart deployment/config-server deployment/keygen-service \
  deployment/shortener-service deployment/resolver-service -n hopr
```

`build-and-load.sh` alone does not restart running pods — since
`imagePullPolicy: Never` and the image tag stays `local`, Kubernetes won't
detect a change on its own; `kubectl rollout restart` forces pods to be
recreated against the freshly loaded image.

### TLS

The ingress-nginx controller terminates TLS: it holds the certificate and private key, and
proxies plain HTTP to the Services inside the cluster. `force-ssl-redirect` on both Ingresses
means plain HTTP from outside is `308`-redirected to HTTPS, so nothing reaches a Service in
cleartext from off-cluster.

`deploy.sh` mints a **self-signed** certificate into `k8s/.dev-tls.crt` / `.dev-tls.key` (both
gitignored, generated on first run and reused afterwards) and passes them to the chart with
`helm --set-file tls.crt=... --set-file tls.key=...`, which renders the `hopr-tls` Secret.
Both Ingresses have host-less rules, so every request is served by the controller's catch-all
server — and a catch-all server takes its certificate from the controller's
`--default-ssl-certificate` flag, not from an Ingress `tls:` block. `deploy.sh` therefore
points that flag at `hopr/hopr-tls` when it patches the controller, and checks at the end
that the certificate actually served on :8443 is that one rather than the controller's
built-in fake certificate. (The `tls:` blocks in the Ingresses become the operative reference
the moment the rules gain a real host name — which is what the cert-manager path below
assumes.) Nothing trusts the certificate, so every `curl` below uses `-k` and a browser needs
the warning clicked through.

`deploy.sh` also patches the controller's `hsts-max-age` down to **300 seconds**. The
controller defaults to one year, and it answers for `localhost`, so that default would pin
every `http://localhost:PORT` on your machine — including the Next dev server — for a year,
clearable only via `chrome://net-internals/#hsts`. This matches the Compose gateway; a real
production deployment on a real domain raises it to the standard one year once TLS is proven
stable.

This is **dev only**. A real deployment installs cert-manager and lets it produce that same
`hopr-tls` Secret from a Let's Encrypt certificate — see
[TLS in the root README](../README.md#tls-transport-security) for the concept and the exact
steps that would change. `tls.crt`/`tls.key` have no defaults: rendering the chart without
them fails loudly rather than falling back to some other certificate.

### Why port 8443 (and 8888), not 443/80

`k8s/kind-config.yaml` maps the gateway to **host ports 8888 (HTTP) and 8443 (HTTPS)**
(`https://localhost:8443/`), not ports 80/443. `deploy.sh` also moves the controller's own
HTTPS listener to 8443 (`--https-port`, with the admission webhook shifted to 8444) and turns
on `use-port-in-redirects`, so that `http://localhost:8888/x` redirects to
`https://localhost:8443/x` — a port that actually serves TLS. With the stock settings the
redirect names port 443, which nothing maps here, and the HTTP entry point would be a dead
end. Those host mappings are fixed when the cluster is created, so an older `hopr` cluster
has to be deleted and recreated — `deploy.sh` checks and says so rather than deploying into
a cluster whose HTTPS port goes nowhere. This is deliberate: this repo's own
`docker-compose.yml` stack already binds host port 80 (and 8080/8081/8083/
27017/etc.) when running, and the two setups are meant to coexist without
one blocking the other. If you're not running docker-compose at the same
time, you can change those `hostPort` values to `80`/`443` in `k8s/kind-config.yaml` and
update `config.shortenerDomain` in `k8s/hopr-chart/values.yaml` to match.

### Tearing down

```bash
kind delete cluster --name hopr
```

This deletes everything (cluster, namespace, all Helm releases, all data —
there is no retained volume backing ScyllaDB or Redis once the cluster is gone).

## How to Test

### 1. Check everything is running

```bash
kubectl get pods -n hopr
```

Expected: every pod `1/1 Running` — 3× `hopr-scylladb-N`, 6× `hopr-redis-redis-cluster-N`,
`config-server`, `keygen-service`, `shortener-service`, `resolver-service`,
plus the `ingress-nginx-controller` pod in the `ingress-nginx` namespace.

### 2. Check each app service's health directly

```bash
kubectl exec -n hopr deploy/keygen-service    -- curl -sf http://localhost:8081/actuator/health
kubectl exec -n hopr deploy/shortener-service -- curl -sf http://localhost:8080/actuator/health
kubectl exec -n hopr deploy/resolver-service  -- curl -sf http://localhost:8083/actuator/health
```

Expected: each prints JSON with `"status":"UP"` — `shortener-service` and
`resolver-service` additionally report `"cassandra":{"status":"UP"}` and
`"redis":{"status":"UP","details":{"cluster_size":...}}`.

### 3. End-to-end: shorten a URL through the gateway

```bash
curl -sk -X POST https://localhost:8443/shorten \
  -H "X-API-Key: $(cat k8s/.dev-api-key)" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com"}'
```

`POST /shorten` rejects a request without a valid `X-API-Key` with `401`; the key is
the one `deploy.sh` minted into `k8s/.dev-api-key`. Redirects stay public.

**Important:** the request body field is `longUrl`, not `url` — this is
the exact field name `ShortenRequest` (in `common/src/main/java/.../dto/
ShortenRequest.java`) expects. Sending `{"url": "..."}` gets silently
accepted (no validation error) but persists a row with a `null` long
URL, which then 500s on resolve — this is a pre-existing application-level
gap, not something this k8s deployment introduced or can fix on its own.

Expected response: `{"shortUrl":"https://localhost:8443/<KEY>"}`.

### 4. End-to-end: resolve the shortened URL

```bash
curl -skI https://localhost:8443/<KEY>
```

Expected: `HTTP/1.1 307` with a `Location: https://example.com` header —
this proves direct service-to-service HTTP calls, ScyllaDB persistence, and
the Ingress routing all work together.

### 5. Confirm ScyllaDB persistence directly (optional)

```bash
kubectl exec -n hopr hopr-scylladb-0 -- cqlsh -e \
  "SELECT short_key, long_url, alias FROM hopr.urls"
```

Expected: a row per shortened URL, e.g.
`<KEY> | https://example.com | null`.

## Known Local-Environment Deviations

These are deliberate choices made while getting this running locally —
recorded here so they aren't mistaken for the "correct" production design:

1. **Gateway host ports are 8888/8443, not 80/443** — to avoid colliding with
   the docker-compose stack's ports 80/443, if it's running at the same time on
   the same machine. The controller is reconfigured to serve HTTPS on 8443 so its
   HTTP→HTTPS redirect points at a port that is actually reachable. See "Why port
   8443 (and 8888), not 443/80" above.
2. **The ingress certificate is self-signed** — a real deployment issues one
   through cert-manager + Let's Encrypt. See "TLS" above.
3. **ScyllaDB is a plain StatefulSet, not the Scylla Operator** — the
   operator's CRDs and controller are more machinery than a local kind
   cluster warrants. See "Why Helm for the app layer" above.
4. **Redis uses ephemeral pod storage**, matching the throwaway nature of a
   local test cluster; Scylla's volumes go with `kind delete cluster`.
5. **Config Server uses a TCP probe, not HTTP** — because the
   `config-server` module has no Spring Boot Actuator dependency, unlike
   the other three services.
6. **Bitnami Redis Cluster images are pinned to the `bitnamilegacy/*`
   Docker Hub org** — required since 2025-08-28 for the free tier; if
   Bitnami's licensing changes again, `deploy.sh`'s `--set image.repository=`
   overrides may need updating.
