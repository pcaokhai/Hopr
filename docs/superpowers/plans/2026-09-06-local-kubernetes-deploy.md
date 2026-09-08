# Local Kubernetes Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `kind`-based local Kubernetes deployment for Hopr (5 services + MongoDB + Redis Cluster) for manual testing, without touching the existing Docker Compose / Swarm setup.

**Architecture:** A `kind` cluster named `hopr`, namespace `hopr`. Data layer via Bitnami Helm charts (`mongodb` standalone, `redis-cluster` 6 nodes). App layer via hand-written Deployment+Service manifests per service, config sourced from a ConfigMap/Secret translated from `.env`. App images are built locally and loaded into `kind` directly (`imagePullPolicy: Never`) — no registry involved. `api-gateway` reuses the existing `nginx.conf` unchanged via a ConfigMap mount, exposed as NodePort through kind's `extraPortMappings`.

**Tech Stack:** kind, kubectl, Helm 3, Bitnami `mongodb`/`redis-cluster` charts, Docker, Gradle (existing), Spring Boot Actuator health endpoints (already present in all 3 app services).

**Spec:** `docs/superpowers/specs/2026-09-06-local-kubernetes-deploy-design.md`

## Global Constraints

- Do not modify `docker-compose.yml`, `stack.yml`, or `.env` — this is an additive, local-only deployment path.
- All new files live under `k8s/`.
- `imagePullPolicy: Never` on every app Deployment — images are loaded via `kind load docker-image`, never pulled from a registry.
- Namespace is always `hopr`.
- Data layer uses Bitnami Helm charts: `bitnami/mongodb` (standalone, 1 replica) and `bitnami/redis-cluster` (6 nodes) — matches the spec's chosen topology.
- Health checks use each service's existing `/actuator/health` endpoint (Spring Boot Actuator is already a dependency in `eureka`, `keygen-service`, `shortener-service`, `resolver-service`).
- Scripts use `set -euo pipefail` and must be idempotent (safe to re-run `deploy.sh`).

---

## File Structure

```
k8s/
  kind-config.yaml           # Task 1
  configmap.yaml              # Task 2
  secret.yaml                  # Task 2
  build-and-load.sh            # Task 3
  eureka.yaml                  # Task 4
  keygen-service.yaml          # Task 5
  shortener-service.yaml       # Task 6
  resolver-service.yaml        # Task 7
  api-gateway.yaml              # Task 8
  deploy.sh                     # Task 9
```

---

### Task 1: kind cluster config

**Files:**
- Create: `k8s/kind-config.yaml`

**Interfaces:**
- Produces: a `kind` cluster named `hopr`, with host port `80` mapped to container `30080` (the NodePort api-gateway will bind to in Task 8).

- [ ] **Step 1: Write the kind cluster config**

```yaml
# k8s/kind-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: hopr
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30080
        hostPort: 80
        protocol: TCP
```

- [ ] **Step 2: Create the cluster and verify**

Run:
```bash
kind create cluster --config k8s/kind-config.yaml
kubectl cluster-info --context kind-hopr
```
Expected: `kind-hopr` control plane reported as running, `kubectl cluster-info` prints the API server URL without error.

- [ ] **Step 3: Create the namespace**

Run:
```bash
kubectl create namespace hopr
kubectl get namespace hopr
```
Expected: `namespace/hopr` shown with `STATUS: Active`.

- [ ] **Step 4: Commit**

```bash
git add k8s/kind-config.yaml
git commit -m "feat: add kind cluster config for local k8s testing"
```

---

### Task 2: ConfigMap and Secret from `.env`

**Files:**
- Create: `k8s/configmap.yaml`
- Create: `k8s/secret.yaml`

**Interfaces:**
- Consumes: values currently in `.env` (see spec) — `EUREKA_*`, `MONGODB_DATABASE`, `REDIS_NODE_*`, `REDIS_PORT`, `REDIS_TIMEOUT`, `SPRING_CACHE_TYPE`, `*_SERVICE_PORT`, `SHORT_DOMAIN`.
- Produces: `ConfigMap/hopr-config` and `Secret/hopr-secret` in namespace `hopr`, consumed via `envFrom` by every app Deployment in Tasks 4–8.

Note: `MONGODB_URI` and the Redis node hostnames depend on the Helm release
names chosen in Task 3 (`hopr-mongodb`, `hopr-redis`). This task hard-codes
those hostnames now since the release names are fixed by this plan (Task 3
step 1), so there's no ordering problem — Task 3 must use exactly these
names.

- [ ] **Step 1: Write the ConfigMap**

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: hopr-config
  namespace: hopr
data:
  EUREKA_SERVER_HOST: "eureka-server"
  EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: "http://eureka-server:8761/eureka/"
  EUREKA_SERVER_PORT: "8761"
  MONGODB_DATABASE: "Hopr"
  MONGO_USERNAME: "root"
  MONGODB_URI: "mongodb://root:$(MONGO_PASSWORD)@hopr-mongodb:27017/Hopr?authSource=admin"
  REDIS_NODE_1: "hopr-redis-redis-cluster-0.hopr-redis-redis-cluster-headless:6379"
  REDIS_NODE_2: "hopr-redis-redis-cluster-1.hopr-redis-redis-cluster-headless:6379"
  REDIS_NODE_3: "hopr-redis-redis-cluster-2.hopr-redis-redis-cluster-headless:6379"
  REDIS_NODE_4: "hopr-redis-redis-cluster-3.hopr-redis-redis-cluster-headless:6379"
  REDIS_NODE_5: "hopr-redis-redis-cluster-4.hopr-redis-redis-cluster-headless:6379"
  REDIS_NODE_6: "hopr-redis-redis-cluster-5.hopr-redis-redis-cluster-headless:6379"
  REDIS_PORT: "6379"
  REDIS_TIMEOUT: "6000"
  SPRING_CACHE_TYPE: "redis"
  SHORTENER_SERVICE_PORT: "8080"
  RESOLVER_SERVICE_PORT: "8083"
  KEYGEN_SERVICE_PORT: "8081"
  SHORT_DOMAIN: "http://localhost/"
```

Note: `$(MONGO_PASSWORD)` in a ConfigMap value is literal text, not
interpolated by Kubernetes. Spring Boot's own property placeholder
resolution (`${MONGO_PASSWORD}`) also won't reach into another ConfigMap
key. To keep this simple, `MONGODB_URI` is split into a template the app
build has to consume alongside `MONGO_PASSWORD` from the Secret — see Step 2:
the Secret carries `MONGO_PASSWORD` and `REDIS_PASSWORD`, and each service's
`application.yml` already builds its Mongo URI from discrete
`MONGO_USERNAME`/`MONGO_PASSWORD`/host/db properties (verify this in Task 4's
verification step). If a service instead expects a single pre-built
`MONGODB_URI` string with the password embedded, replace this ConfigMap key
with a Secret key instead (`stringData.MONGODB_URI` in Step 2) so the
password isn't left as a literal unresolved placeholder.

- [ ] **Step 2: Write the Secret**

```yaml
# k8s/secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: hopr-secret
  namespace: hopr
type: Opaque
stringData:
  MONGO_PASSWORD: "password"
  REDIS_PASSWORD: ""
```

- [ ] **Step 3: Apply and verify**

Run:
```bash
kubectl apply -f k8s/configmap.yaml -f k8s/secret.yaml
kubectl get configmap hopr-config -n hopr -o yaml
kubectl get secret hopr-secret -n hopr
```
Expected: both resources exist in namespace `hopr`; `kubectl get configmap ... -o yaml` shows the keys listed above.

- [ ] **Step 4: Commit**

```bash
git add k8s/configmap.yaml k8s/secret.yaml
git commit -m "feat: add k8s ConfigMap/Secret translated from .env"
```

---

### Task 3: Data layer (MongoDB + Redis Cluster via Helm)

**Files:**
- None created — this task only records the exact Helm commands `deploy.sh` (Task 9) will call, and verifies them manually first.

**Interfaces:**
- Produces: Services `hopr-mongodb` (port 27017) and `hopr-redis-redis-cluster-headless` (StatefulSet-backed, 6 pods, port 6379) in namespace `hopr` — these are the hostnames hard-coded into `k8s/configmap.yaml` in Task 2.

- [ ] **Step 1: Add the Bitnami Helm repo**

Run:
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```
Expected: `"bitnami" has been added to your repositories` (or already present), then repo update succeeds.

- [ ] **Step 2: Install MongoDB**

Run:
```bash
helm install hopr-mongodb bitnami/mongodb \
  --namespace hopr \
  --set architecture=standalone \
  --set auth.rootPassword=password \
  --set auth.database=Hopr
```
Expected: `helm install` reports `STATUS: deployed`. Confirm the release name matches `hopr-mongodb`, matching `MONGODB_URI` in `k8s/configmap.yaml`.

- [ ] **Step 3: Install Redis Cluster**

Run:
```bash
helm install hopr-redis bitnami/redis-cluster \
  --namespace hopr \
  --set cluster.nodes=6 \
  --set usePassword=false
```
Expected: `helm install` reports `STATUS: deployed`.

- [ ] **Step 4: Verify both are ready**

Run:
```bash
kubectl get pods -n hopr -l app.kubernetes.io/instance=hopr-mongodb
kubectl get pods -n hopr -l app.kubernetes.io/instance=hopr-redis
kubectl get svc -n hopr
```
Expected: MongoDB pod `1/1 Running`; 6 Redis pods `1/1 Running`; `kubectl get svc` lists `hopr-mongodb` and `hopr-redis-redis-cluster-headless` (or the exact name Bitnami's chart generates — record the actual name here if it differs and fix `k8s/configmap.yaml` in Task 2 to match before proceeding).

- [ ] **Step 5: Commit the recorded Helm commands into deploy notes**

These commands get folded verbatim into `k8s/deploy.sh` in Task 9 — no separate file to commit here. Skip to Task 4.

---

### Task 4: Eureka service manifest

**Files:**
- Create: `k8s/eureka.yaml`

**Interfaces:**
- Consumes: `ConfigMap/hopr-config`, `Secret/hopr-secret` (Task 2); image `hopr/eureka:local` (built in Task 9).
- Produces: `Service/eureka-server` (ClusterIP, port 8761) — the hostname every other app service's `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` points to.

- [ ] **Step 1: Confirm the actuator health path**

Run:
```bash
grep -r "actuator" eureka/src/main/resources/application.yml eureka/build.gradle.kts
```
Expected: `spring-boot-starter-actuator` present in `build.gradle.kts`. If `application.yml` sets a custom `management.endpoints.web.base-path`, use that path instead of `/actuator/health` in Step 2 below.

- [ ] **Step 2: Write the manifest**

```yaml
# k8s/eureka.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: eureka-server
  namespace: hopr
spec:
  replicas: 1
  selector:
    matchLabels:
      app: eureka-server
  template:
    metadata:
      labels:
        app: eureka-server
    spec:
      containers:
        - name: eureka-server
          image: hopr/eureka:local
          imagePullPolicy: Never
          ports:
            - containerPort: 8761
          envFrom:
            - configMapRef:
                name: hopr-config
            - secretRef:
                name: hopr-secret
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8761
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8761
            initialDelaySeconds: 30
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: eureka-server
  namespace: hopr
spec:
  selector:
    app: eureka-server
  ports:
    - port: 8761
      targetPort: 8761
```

- [ ] **Step 3: Build and load the eureka image, then apply**

Run:
```bash
(cd eureka && ../gradlew build -x test)
docker build -t hopr/eureka:local eureka/
kind load docker-image hopr/eureka:local --name hopr
kubectl apply -f k8s/eureka.yaml
kubectl rollout status deployment/eureka-server -n hopr --timeout=120s
```
Expected: rollout succeeds (`deployment "eureka-server" successfully rolled out`).

- [ ] **Step 4: Verify health**

Run:
```bash
kubectl exec -n hopr deploy/eureka-server -- curl -sf http://localhost:8761/actuator/health
```
Expected: JSON containing `"status":"UP"`.

- [ ] **Step 5: Commit**

```bash
git add k8s/eureka.yaml
git commit -m "feat: add eureka-server k8s manifest"
```

---

### Task 5: Keygen service manifest

**Files:**
- Create: `k8s/keygen-service.yaml`

**Interfaces:**
- Consumes: `ConfigMap/hopr-config`, `Secret/hopr-secret`, `Service/eureka-server:8761` (Task 4), `hopr-mongodb` (Task 3); image `hopr/keygen-service:local`.
- Produces: `Service/keygen-service` (ClusterIP, port 8081).

- [ ] **Step 1: Write the manifest**

```yaml
# k8s/keygen-service.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keygen-service
  namespace: hopr
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keygen-service
  template:
    metadata:
      labels:
        app: keygen-service
    spec:
      containers:
        - name: keygen-service
          image: hopr/keygen-service:local
          imagePullPolicy: Never
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: hopr-config
            - secretRef:
                name: hopr-secret
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 25
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 40
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: keygen-service
  namespace: hopr
spec:
  selector:
    app: keygen-service
  ports:
    - port: 8081
      targetPort: 8081
```

- [ ] **Step 2: Build, load, apply**

Run:
```bash
(cd keygen-service && ../gradlew build -x test)
docker build -t hopr/keygen-service:local keygen-service/
kind load docker-image hopr/keygen-service:local --name hopr
kubectl apply -f k8s/keygen-service.yaml
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
```
Expected: rollout succeeds.

- [ ] **Step 3: Verify health and Eureka registration**

Run:
```bash
kubectl exec -n hopr deploy/keygen-service -- curl -sf http://localhost:8081/actuator/health
kubectl exec -n hopr deploy/eureka-server -- curl -s http://localhost:8761/eureka/apps/KEYGEN-SERVICE
```
Expected: health returns `"status":"UP"`; the Eureka apps query returns XML containing an `<instance>` entry (service name may differ — check `spring.application.name` in `keygen-service/src/main/resources/application.yml` and adjust the URL casing if needed).

- [ ] **Step 4: Commit**

```bash
git add k8s/keygen-service.yaml
git commit -m "feat: add keygen-service k8s manifest"
```

---

### Task 6: Shortener service manifest

**Files:**
- Create: `k8s/shortener-service.yaml`

**Interfaces:**
- Consumes: `ConfigMap/hopr-config`, `Secret/hopr-secret`, `Service/eureka-server` (Task 4), `Service/keygen-service` (Task 5, via Eureka discovery — not a direct k8s Service reference), `hopr-mongodb` and Redis cluster (Task 3); image `hopr/shortener-service:local`.
- Produces: `Service/shortener-service` (ClusterIP, port 8080) — referenced by `api-gateway`'s nginx upstream in Task 8.

- [ ] **Step 1: Write the manifest**

```yaml
# k8s/shortener-service.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: shortener-service
  namespace: hopr
spec:
  replicas: 1
  selector:
    matchLabels:
      app: shortener-service
  template:
    metadata:
      labels:
        app: shortener-service
    spec:
      containers:
        - name: shortener-service
          image: hopr/shortener-service:local
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: hopr-config
            - secretRef:
                name: hopr-secret
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 25
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 40
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: shortener-service
  namespace: hopr
spec:
  selector:
    app: shortener-service
  ports:
    - port: 8080
      targetPort: 8080
```

- [ ] **Step 2: Build, load, apply**

Run:
```bash
(cd shortener-service && ../gradlew build -x test)
docker build -t hopr/shortener-service:local shortener-service/
kind load docker-image hopr/shortener-service:local --name hopr
kubectl apply -f k8s/shortener-service.yaml
kubectl rollout status deployment/shortener-service -n hopr --timeout=120s
```
Expected: rollout succeeds.

- [ ] **Step 3: Verify health**

Run:
```bash
kubectl exec -n hopr deploy/shortener-service -- curl -sf http://localhost:8080/actuator/health
```
Expected: `"status":"UP"`.

- [ ] **Step 4: Commit**

```bash
git add k8s/shortener-service.yaml
git commit -m "feat: add shortener-service k8s manifest"
```

---

### Task 7: Resolver service manifest

**Files:**
- Create: `k8s/resolver-service.yaml`

**Interfaces:**
- Consumes: `ConfigMap/hopr-config`, `Secret/hopr-secret`, `Service/eureka-server` (Task 4), `hopr-mongodb`/Redis (Task 3); image `hopr/resolver-service:local`.
- Produces: `Service/resolver-service` (ClusterIP, port 8083) — referenced by `api-gateway`'s nginx upstream in Task 8.

- [ ] **Step 1: Write the manifest**

```yaml
# k8s/resolver-service.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resolver-service
  namespace: hopr
spec:
  replicas: 1
  selector:
    matchLabels:
      app: resolver-service
  template:
    metadata:
      labels:
        app: resolver-service
    spec:
      containers:
        - name: resolver-service
          image: hopr/resolver-service:local
          imagePullPolicy: Never
          ports:
            - containerPort: 8083
          envFrom:
            - configMapRef:
                name: hopr-config
            - secretRef:
                name: hopr-secret
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8083
            initialDelaySeconds: 25
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8083
            initialDelaySeconds: 40
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: resolver-service
  namespace: hopr
spec:
  selector:
    app: resolver-service
  ports:
    - port: 8083
      targetPort: 8083
```

- [ ] **Step 2: Build, load, apply**

Run:
```bash
(cd resolver-service && ../gradlew build -x test)
docker build -t hopr/resolver-service:local resolver-service/
kind load docker-image hopr/resolver-service:local --name hopr
kubectl apply -f k8s/resolver-service.yaml
kubectl rollout status deployment/resolver-service -n hopr --timeout=120s
```
Expected: rollout succeeds.

- [ ] **Step 3: Verify health**

Run:
```bash
kubectl exec -n hopr deploy/resolver-service -- curl -sf http://localhost:8083/actuator/health
```
Expected: `"status":"UP"`.

- [ ] **Step 4: Commit**

```bash
git add k8s/resolver-service.yaml
git commit -m "feat: add resolver-service k8s manifest"
```

---

### Task 8: API gateway manifest

**Files:**
- Create: `k8s/api-gateway.yaml`

**Interfaces:**
- Consumes: existing `api-gateway/nginx.conf` (unchanged), `Service/shortener-service` (Task 6), `Service/resolver-service` (Task 7).
- Produces: `Service/api-gateway` (NodePort `30080`, matching `k8s/kind-config.yaml`'s `extraPortMappings` from Task 1) — the entry point for end-to-end testing in Task 10.

- [ ] **Step 1: Create the nginx ConfigMap and manifest**

```bash
kubectl create configmap api-gateway-nginx-conf \
  --from-file=nginx.conf=api-gateway/nginx.conf \
  --namespace hopr \
  --dry-run=client -o yaml > k8s/api-gateway.yaml
```

Then append the Deployment and Service to the same file (edit `k8s/api-gateway.yaml` so it contains all three documents separated by `---`):

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: hopr
spec:
  replicas: 1
  selector:
    matchLabels:
      app: api-gateway
  template:
    metadata:
      labels:
        app: api-gateway
    spec:
      containers:
        - name: api-gateway
          image: nginx:latest
          ports:
            - containerPort: 80
          volumeMounts:
            - name: nginx-conf
              mountPath: /etc/nginx/nginx.conf
              subPath: nginx.conf
      volumes:
        - name: nginx-conf
          configMap:
            name: api-gateway-nginx-conf
---
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
  namespace: hopr
spec:
  type: NodePort
  selector:
    app: api-gateway
  ports:
    - port: 80
      targetPort: 80
      nodePort: 30080
```

- [ ] **Step 2: Apply and verify**

Run:
```bash
kubectl apply -f k8s/api-gateway.yaml
kubectl rollout status deployment/api-gateway -n hopr --timeout=60s
kubectl get svc api-gateway -n hopr
```
Expected: rollout succeeds; Service shows `TYPE: NodePort`, `PORT(S): 80:30080/TCP`.

- [ ] **Step 3: Commit**

```bash
git add k8s/api-gateway.yaml
git commit -m "feat: add api-gateway k8s manifest"
```

---

### Task 9: Deploy and build-and-load scripts

**Files:**
- Create: `k8s/build-and-load.sh`
- Create: `k8s/deploy.sh`

**Interfaces:**
- Consumes: every file from Tasks 1–8, plus the exact Helm commands recorded in Task 3.
- Produces: a single command (`./k8s/deploy.sh`) that reproduces the entire stack from scratch — the deliverable the spec's Testing section verifies.

- [ ] **Step 1: Write `build-and-load.sh`**

```bash
#!/usr/bin/env bash
# k8s/build-and-load.sh
set -euo pipefail

cd "$(dirname "$0")/.."

for service in eureka keygen-service shortener-service resolver-service; do
  echo "Building $service..."
  (cd "$service" && ../gradlew build -x test)
  docker build -t "hopr/$service:local" "$service/"
  kind load docker-image "hopr/$service:local" --name hopr
done

echo "All images built and loaded into kind cluster 'hopr'."
```

- [ ] **Step 2: Make it executable and run it standalone**

Run:
```bash
chmod +x k8s/build-and-load.sh
./k8s/build-and-load.sh
docker exec hopr-control-plane crictl images | grep hopr/
```
Expected: script exits 0; all 4 `hopr/*:local` images are present inside the kind node.

- [ ] **Step 3: Write `deploy.sh`**

```bash
#!/usr/bin/env bash
# k8s/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if ! kind get clusters | grep -q '^hopr$'; then
  kind create cluster --config k8s/kind-config.yaml
fi

kubectl create namespace hopr --dry-run=client -o yaml | kubectl apply -f -

helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null 2>&1 || true
helm repo update

helm upgrade --install hopr-mongodb bitnami/mongodb \
  --namespace hopr \
  --set architecture=standalone \
  --set auth.rootPassword=password \
  --set auth.database=Hopr \
  --wait --timeout 5m

helm upgrade --install hopr-redis bitnami/redis-cluster \
  --namespace hopr \
  --set cluster.nodes=6 \
  --set usePassword=false \
  --wait --timeout 5m

kubectl apply -f k8s/configmap.yaml -f k8s/secret.yaml

./k8s/build-and-load.sh

kubectl apply -f k8s/eureka.yaml
kubectl rollout status deployment/eureka-server -n hopr --timeout=120s

kubectl apply -f k8s/keygen-service.yaml -f k8s/shortener-service.yaml -f k8s/resolver-service.yaml
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
kubectl rollout status deployment/shortener-service -n hopr --timeout=120s
kubectl rollout status deployment/resolver-service -n hopr --timeout=120s

kubectl apply -f k8s/api-gateway.yaml
kubectl rollout status deployment/api-gateway -n hopr --timeout=60s

echo "Hopr is up. Try: curl -X POST localhost/shorten -d '{\"url\":\"https://example.com\"}' -H 'Content-Type: application/json'"
```

- [ ] **Step 4: Make it executable**

Run:
```bash
chmod +x k8s/deploy.sh
```

- [ ] **Step 5: Tear down and re-run end-to-end to prove idempotency**

Run:
```bash
kind delete cluster --name hopr
./k8s/deploy.sh
kubectl get pods -n hopr
```
Expected: script completes without error on a from-scratch cluster; `kubectl get pods -n hopr` shows all pods `Running`/`Ready` (1 mongodb, 6 redis, eureka-server, keygen-service, shortener-service, resolver-service, api-gateway).

Run it a second time without deleting the cluster:
```bash
./k8s/deploy.sh
```
Expected: exits 0, no errors (Helm `upgrade --install` and `kubectl apply` are idempotent).

- [ ] **Step 6: Commit**

```bash
git add k8s/build-and-load.sh k8s/deploy.sh
git commit -m "feat: add deploy.sh orchestration script for local k8s stack"
```

---

### Task 10: End-to-end verification

**Files:**
- None created — this task only exercises the running stack per the spec's Testing section.

**Interfaces:**
- Consumes: the fully deployed stack from Task 9.

- [ ] **Step 1: Shorten a URL through the gateway**

Run:
```bash
curl -s -X POST http://localhost/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'
```
Expected: a JSON response containing a short key/URL (exact field name depends on `shortener-service`'s DTO — check `shortener-service/src/main/java/**/dto/*Response*.java` if the shape is unexpected). Record the short key returned, call it `<KEY>`.

- [ ] **Step 2: Resolve the shortened URL**

Run:
```bash
curl -sI http://localhost/<KEY>
```
Expected: an HTTP redirect response (`301`/`302`/`307`) with a `Location: https://example.com` header — proving Eureka discovery (gateway → shortener/resolver), MongoDB persistence, and Redis caching all work together.

- [ ] **Step 3: Confirm Mongo persistence directly**

Run:
```bash
kubectl exec -n hopr -it $(kubectl get pod -n hopr -l app.kubernetes.io/name=mongodb -o jsonpath='{.items[0].metadata.name}') -- \
  mongosh "mongodb://root:password@localhost:27017/Hopr?authSource=admin" --eval "db.getCollectionNames()"
```
Expected: prints the collection(s) shortener-service writes to (verify the exact collection name in `shortener-service/src/main/java/**/repository/*.java` if this list is empty and the test above returned no error).

- [ ] **Step 4: No commit** — this task is verification only, nothing to add to git.

---

## Self-Review Notes

- Spec coverage: kind cluster (Task 1), ConfigMap/Secret (Task 2), Bitnami Helm data layer (Task 3), all 4 app services (Tasks 4–7), api-gateway with unchanged nginx.conf (Task 8), build-and-load + deploy scripts (Task 9), end-to-end curl verification (Task 10) — matches every section of the spec.
- No `docker-compose.yml`/`stack.yml`/`.env` file is modified by any task.
- Task 2's `MONGODB_URI` templating caveat is flagged explicitly with a concrete fallback (move it to the Secret) rather than left as a silent assumption — the executor must check the actual `application.yml` binding in Task 4 and adjust before relying on it in Task 6/7.
- Redis/Mongo hostnames in Task 2 assume Bitnami's default naming convention for release names `hopr-mongodb` and `hopr-redis`; Task 3 Step 4 explicitly tells the executor to reconcile the two if the generated names differ.
