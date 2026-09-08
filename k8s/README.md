# Hopr — Local Kubernetes Deployment

This directory contains a **local-only** Kubernetes deployment of the Hopr
stack, built for manual testing on `kind`. It does not replace or modify
`docker-compose.yml`, `stack.yml`, or `.env` at the repo root — those remain
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
         ├─ Deployment/Service: hopr-mongodb        (plain mongo:7.0 image)
         ├─ Deployment/Service: config-server         (port 8888)
         ├─ Deployment/Service: keygen-service        (port 8081)
         ├─ Deployment/Service: shortener-service     (port 8080)
         └─ Deployment/Service: resolver-service       (port 8083)
     └─ ingress-nginx (installed separately via upstream manifest)
         └─ Ingress: hopr-ingress — routes /shorten and / to shortener-service,
                     everything else to resolver-service
```

App images (`hopr/config-server`, `hopr/keygen-service`,
`hopr/shortener-service`, `hopr/resolver-service`) are built locally from the
existing Dockerfiles and loaded straight into the `kind` node with `kind load
docker-image` — `imagePullPolicy: Never` everywhere, no image registry
involved. The gateway is no longer a hand-rolled nginx pod in the app chart —
`deploy.sh` installs the upstream `ingress-nginx` controller into the cluster,
and `k8s/hopr-chart/templates/ingress.yaml` defines a native Kubernetes
`Ingress` resource that the controller reads to route traffic to
`shortener-service` and `resolver-service`.

## File Layout

```
k8s/
  kind-config.yaml          kind cluster definition (1 node, port mapping)
  build-and-load.sh         builds jars + docker images, loads them into kind
  deploy.sh                 full deploy: cluster, Redis, app chart, rollout wait
  hopr-chart/                Helm chart for MongoDB + all 4 app services + Ingress
    Chart.yaml
    values.yaml              all tunable values (ports, image tag, mongo/redis config)
    templates/
      configmap.yaml          non-secret env vars (Redis nodes, ports...)
      secret.yaml             MONGO_URI, REDIS_PASSWORD
      mongodb.yaml            MongoDB Deployment + Service
      config-server.yaml      Config Server Deployment + Service (port 8888)
      keygen-service.yaml     keygen-service Deployment + Service
      shortener-service.yaml  shortener-service Deployment + Service
      resolver-service.yaml   resolver-service Deployment + Service
      ingress.yaml            Ingress resource routing to shortener-service /
                              resolver-service via the ingress-nginx controller
```

## Why Helm for the app layer, and why MongoDB isn't a Bitnami chart

- **Redis Cluster** uses the `bitnami/redis-cluster` chart directly — it
  matches the existing docker-compose topology (6 nodes) and its image is
  multi-arch (works on both Apple Silicon and Intel/amd64 hosts).
- **MongoDB** does **not** use the `bitnami/mongodb` chart. Since 2025-08-28,
  Bitnami's free-tier images have moved to the `bitnamilegacy/*` Docker Hub
  org, and `bitnamilegacy/mongodb` is **amd64-only** — it fails to pull on
  arm64 hosts (e.g. Apple Silicon Macs) with `no match for platform in
  manifest`. Since correctness for local testing matters more than reusing
  a chart, MongoDB is deployed as a plain `Deployment`/`Service` using the
  same `mongo:7.0` image already used in `docker-compose.yml`.
- The **app services** (config-server, keygen, shortener, resolver) have no
  third-party chart to reuse — they're this project's own code — so they're
  packaged as a small first-party chart (`k8s/hopr-chart`) instead of loose
  `kubectl apply -f` manifests, for versioning and easier `values.yaml`
  overrides (port, image tag, Mongo/Redis config). The gateway itself is a
  native Kubernetes `Ingress` resource (`templates/ingress.yaml`), routed by
  the upstream `ingress-nginx` controller that `deploy.sh` installs
  separately — not an app-chart pod.

If the Bitnami mongodb chart's arm64 support improves in the future, or if
this always runs on amd64 CI, switching `templates/mongodb.yaml` back to a
`bitnami/mongodb` Helm dependency is a contained change — the app's
ConfigMap/Secret already isolate the Mongo connection details.

## Environment Variables — Mapping from `.env`

The k8s ConfigMap/Secret intentionally use the **exact env var names each
service's `application.yml` expects** (verified by reading
`*/src/main/resources/application.yml` in each service), which differ
slightly from the root `.env` file's naming:

| `.env` name (compose)                  | k8s name (what the app actually reads) |
|-----------------------------------------|------------------------------------------|
| `MONGODB_URI`                           | `MONGO_URI`                              |
| `SHORTENER_SERVICE_PORT`                | `SHORTENER_SERVER_PORT`                  |
| `RESOLVER_SERVICE_PORT`                 | `RESOLVER_SERVER_PORT`                   |
| `KEYGEN_SERVICE_PORT`                   | `KEYGEN_SERVER_PORT`                     |
| `SHORT_DOMAIN`                          | `SHORTENER_DOMAIN`                       |

Non-secret values (Redis node list, ports, domain) live in `hopr-config` (a
ConfigMap); credentials (`MONGO_URI` with the password embedded,
`REDIS_PASSWORD`) live in `hopr-secret`. Both are rendered from
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
1. Create the `kind` cluster `hopr` if it doesn't already exist
   (`k8s/kind-config.yaml` maps the node's container port 80 → host port
   **8888**, and labels the node `ingress-ready=true` for `ingress-nginx`).
2. Create the `hopr` namespace.
3. `helm upgrade --install hopr-redis bitnami/redis-cluster` (6 nodes,
   `bitnamilegacy/*` images — see note below).
4. Install the upstream `ingress-nginx` controller and wait for it to
   become ready.
5. Build all 4 app jars with Gradle, build their Docker images, and
   `kind load docker-image` them into the cluster (`k8s/build-and-load.sh`).
6. `helm upgrade --install hopr ./k8s/hopr-chart` — deploys MongoDB, all 4
   app services, and the `Ingress` resource.
7. Wait for every Deployment's rollout to finish.

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

### Why port 8888, not 80

`k8s/kind-config.yaml` maps the gateway to **host port 8888**
(`http://localhost:8888/`), not port 80. This is deliberate: this repo's own
`docker-compose.yml` stack already binds host port 80 (and 8080/8081/8083/
27017/etc.) when running, and the two setups are meant to coexist without
one blocking the other. If you're not running docker-compose at the same
time, you can change `hostPort: 8888` to `80` in `k8s/kind-config.yaml` and
update `config.shortenerDomain` in `k8s/hopr-chart/values.yaml` to match.

### Tearing down

```bash
kind delete cluster --name hopr
```

This deletes everything (cluster, namespace, all Helm releases, all data —
there is no persistent volume backing MongoDB or Redis in this local setup).

## How to Test

### 1. Check everything is running

```bash
kubectl get pods -n hopr
```

Expected: every pod `1/1 Running` — `hopr-mongodb`, 6× `hopr-redis-redis-cluster-N`,
`config-server`, `keygen-service`, `shortener-service`, `resolver-service`,
plus the `ingress-nginx-controller` pod in the `ingress-nginx` namespace.

### 2. Check each app service's health directly

```bash
kubectl exec -n hopr deploy/keygen-service    -- curl -sf http://localhost:8081/actuator/health
kubectl exec -n hopr deploy/shortener-service -- curl -sf http://localhost:8080/actuator/health
kubectl exec -n hopr deploy/resolver-service  -- curl -sf http://localhost:8083/actuator/health
```

Expected: each prints JSON with `"status":"UP"` — `shortener-service` and
`resolver-service` additionally report `"mongo":{"status":"UP"}` and
`"redis":{"status":"UP","details":{"cluster_size":...}}`.

### 3. End-to-end: shorten a URL through the gateway

```bash
curl -s -X POST http://localhost:8888/shorten \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com"}'
```

**Important:** the request body field is `longUrl`, not `url` — this is
the exact field name `ShortenRequest` (in `common/src/main/java/.../dto/
ShortenRequest.java`) expects. Sending `{"url": "..."}` gets silently
accepted (no validation error) but persists a document with a `null` long
URL, which then 500s on resolve — this is a pre-existing application-level
gap, not something this k8s deployment introduced or can fix on its own.

Expected response: `{"shortUrl":"http://localhost:8888/<KEY>"}`.

### 4. End-to-end: resolve the shortened URL

```bash
curl -sI http://localhost:8888/<KEY>
```

Expected: `HTTP/1.1 307` with a `Location: https://example.com` header —
this proves direct service-to-service HTTP calls, MongoDB persistence, and
the Ingress routing all work together.

### 5. Confirm MongoDB persistence directly (optional)

```bash
POD=$(kubectl get pod -n hopr -l app=hopr-mongodb -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n hopr "$POD" -- mongosh "mongodb://root:password@localhost:27017/Hopr?authSource=admin" \
  --quiet --eval "printjson(db.urls.find().toArray())"
```

Expected: a document per shortened URL, e.g.
`{ _id: '<KEY>', longUrl: 'https://example.com', ... }`.

## Known Local-Environment Deviations

These are deliberate choices made while getting this running locally —
recorded here so they aren't mistaken for the "correct" production design:

1. **Gateway host port is 8888, not 80** — to avoid colliding with the
   docker-compose stack's port 80, if it's running at the same time on the
   same machine. See "Why port 8888, not 80" above.
2. **MongoDB is a plain Deployment, not a Bitnami Helm chart** — because
   Bitnami's free-tier legacy image for MongoDB has no arm64 build. See
   "Why Helm for the app layer" above.
3. **No PersistentVolumeClaims** — MongoDB and Redis both use ephemeral
   pod storage, matching the throwaway nature of a local test cluster.
   Data is lost on pod restart or `kind delete cluster`.
4. **Config Server uses a TCP probe, not HTTP** — because the
   `config-server` module has no Spring Boot Actuator dependency, unlike
   the other three services.
5. **Bitnami Redis Cluster images are pinned to the `bitnamilegacy/*`
   Docker Hub org** — required since 2025-08-28 for the free tier; if
   Bitnami's licensing changes again, `deploy.sh`'s `--set image.repository=`
   overrides may need updating.
