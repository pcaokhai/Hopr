# Local Kubernetes Deployment for Hopr

Date: 2026-09-06

## Purpose

Hopr currently only ships a Docker Compose stack (`docker-compose.yml`) and a
Swarm stack (`stack.yml`). There is no way to run the system on Kubernetes.
This adds a **local-only** Kubernetes deployment (via `kind`) for manual
testing, alongside the existing Compose/Swarm files — neither is modified or
replaced.

## Scope

In scope: a `kind` cluster config, manifests/scripts to deploy all 5 Hopr
services plus MongoDB and Redis Cluster into that cluster, and a way to build
and load app images without a registry.

Out of scope: production Kubernetes (ingress, TLS, HPA, resource limits
tuning, CI integration, cloud-managed Mongo/Redis) — this is a local test
environment only.

## Architecture

```
kind cluster "hopr"
 └─ namespace: hopr
     ├─ Helm: bitnami/mongodb (standalone, 1 replica)
     ├─ Helm: bitnami/redis-cluster (6 nodes)
     ├─ Deployment+Service: eureka-server (8761)
     ├─ Deployment+Service: keygen-service (8081)
     ├─ Deployment+Service: shortener-service (8080)
     ├─ Deployment+Service: resolver-service (8083)
     └─ Deployment+Service (NodePort via kind extraPortMappings): api-gateway (80)
```

Config values currently in `.env` become a `ConfigMap` (`hopr-config`) for
non-secret values (Eureka URLs, service ports, Redis node list, domain) and a
`Secret` (`hopr-secret`) for `MONGO_PASSWORD` / `REDIS_PASSWORD`. Values that
depend on the Helm release name (Mongo/Redis host) are filled in after `helm
install` reports the generated service names.

## Components

### Cluster (`k8s/kind-config.yaml`)

Single-node `kind` cluster named `hopr`, with `extraPortMappings` mapping
host `80` → container `30080` (NodePort) so `curl localhost/...` behaves like
the current Compose setup.

### Data layer (Helm, Bitnami charts)

- `bitnami/mongodb`: `architecture=standalone`, root password from
  `MONGO_PASSWORD`, database `Hopr`. Replaces MongoDB Atlas for local testing
  only — Atlas is untouched for other environments.
- `bitnami/redis-cluster`: 6 nodes (`cluster.nodes=6`), matching the existing
  docker-compose topology. Password left empty to match current `.env`
  default.

Both installed into the `hopr` namespace via `helm install`, no custom values
files beyond what's passed on the command line in `deploy.sh` — no need for a
maintained `values.yaml` for a local-only setup.

### App services (plain manifests, `k8s/*.yaml`)

Each of eureka-server, keygen-service, shortener-service, resolver-service is
one file with a `Deployment` (1 replica, image `hopr/<service>:local`,
`imagePullPolicy: Never` since images are loaded directly into kind) and a
`ClusterIP` `Service`. Env vars come from `envFrom: configMapRef/secretRef`.

Readiness/liveness probes use each service's existing Spring Boot Actuator
health endpoint (`/actuator/health`) — already a dependency in all three app
services' `build.gradle.kts`.

`api-gateway` reuses the existing `api-gateway/nginx.conf` unchanged, mounted
via a `ConfigMap` (`kubectl create configmap --from-file`), exposed as a
`NodePort` Service on `30080`.

### Build & load (`k8s/build-and-load.sh`)

For each of the 4 app services (eureka, keygen, shortener, resolver):
`docker build -t hopr/<service>:local <dir>` then
`kind load docker-image hopr/<service>:local --name hopr`. No registry, no
push — this is why `imagePullPolicy: Never` is used.

### Deploy (`k8s/deploy.sh`)

1. `kind create cluster --config k8s/kind-config.yaml` (skip if cluster `hopr`
   already exists)
2. `helm install hopr-mongodb bitnami/mongodb -n hopr --create-namespace ...`
3. `helm install hopr-redis bitnami/redis-cluster -n hopr ...`
4. `kubectl apply -f k8s/configmap.yaml -f k8s/secret.yaml`
5. `./k8s/build-and-load.sh`
6. `kubectl apply -f k8s/eureka.yaml`, wait for ready, then apply
   keygen/shortener/resolver, then api-gateway — ordering matches the
   service-discovery dependency (Eureka must be up first).

## Error Handling

- `deploy.sh` uses `set -euo pipefail`; any failed step aborts the script
  with the failing command visible.
- Script checks `kind get clusters` before creating, so re-running `deploy.sh`
  is idempotent for the cluster step (Helm/kubectl apply are already
  idempotent).

## Testing

Manual verification only, matching how the Compose stack is currently
verified (no existing automated deploy tests in this repo):
1. `./k8s/deploy.sh` completes without error.
2. `kubectl get pods -n hopr` — all pods `Running`/`Ready`.
3. `curl localhost/api/shorten -d '{"url":"https://example.com"}'` (or
   whatever the shortener endpoint is) returns a short URL, and following the
   redirect resolves — proving Eureka discovery, Redis cache, and Mongo
   persistence all work end-to-end.

## File Layout

```
k8s/
  kind-config.yaml
  configmap.yaml
  secret.yaml
  eureka.yaml
  keygen-service.yaml
  shortener-service.yaml
  resolver-service.yaml
  api-gateway.yaml
  build-and-load.sh
  deploy.sh
```

No changes to `docker-compose.yml`, `stack.yml`, or `.env`.
