# Hopr: Docker Compose Setup & Testing Guide

This guide provides step-by-step instructions to build, run, and test the entire Hopr URL shortener ecosystem (including Config Server, API Gateway, Microservices, ScyllaDB, and Redis Cluster) using **Docker Compose** in a local environment.

---

## 1. Architecture & Services Overview

All services run inside a dedicated Docker bridge network (`hopr_default`):

| Service Name | Container Name | Port (Host : Container) | Description |
| :--- | :--- | :--- | :--- |
| **`api-gateway`** | `hopr-api-gateway` | `80:80`, `443:443` | Nginx reverse proxy terminating TLS (`80` only `301`s to HTTPS) and routing `/shorten` to `shortener-service` and `/{alias}` to `resolver-service`. Rate limited (5 req/s, burst 10). |
| **`config-server`** | `hopr-config-server` | - | Spring Cloud Config Server serving centralized configuration to the microservices (internal only, no host port published). |
| **`keygen-service`** | `hopr-keygen-service` | `8081:8081` | Generates unique random keys for shortened URLs. |
| **`shortener-service`** | `hopr-shortener-service` | `8080:8080` | Handles URL shortening requests, persists to ScyllaDB, caches in Redis, interacts with KeyGen. |
| **`resolver-service`** | `hopr-resolver-service` | `8083:8083` | Resolves short keys, checks Redis cache (falls back to ScyllaDB), returns HTTP 307 redirect. |
| **`scylla-node-1` .. `3`** | `hopr-scylla-node-1..3` | `9042..9044:9042` | 3-node ScyllaDB cluster (keyspace `hopr`, RF 3, schema applied by `db-migration`) — the persistence store behind `shortener-service` and `resolver-service`. |
| **`redis-node-1` .. `6`** | `hopr-redis-node-1..6` | `7001..7006:6379` | 6-node Redis Cluster (3 masters, 3 replicas) for distributed caching. |
| **`redis-cluster-init`** | `hopr-redis-cluster-init` | - | One-shot initialization container to cluster the 6 Redis nodes on startup. |

Persistent data for the 3 ScyllaDB nodes and all 6 Redis nodes is bind-mounted to the `./data/` directory at the project root.

---

## 2. Prerequisites

- **Docker & Docker Compose** (Docker Desktop on macOS/Windows or Docker Engine with Docker Compose V2 on Linux).
- **Java 21+** (Java 21 or Java 25 recommended) to build project JARs.
- **cURL** or an API client (Postman, Bruno, Insomnia, etc.).

---

## 3. Getting Started

### Step 1: Build Application JARs (Gradle)

Because the Dockerfiles copy JARs directly from each service's `build/libs/` directory, you must compile and package the JARs first:

```bash
./gradlew bootJar -x test
```

> **Note:** Omit `-x test` if you wish to run the full unit/integration test suite during compilation.

---

### Step 2: Create the Environment Files (`.env`, `.env.secrets`, `.env.frontend.secrets`)

Neither file is committed. Copy them from the templates on a fresh clone:

```bash
cp .env.example .env
cp .env.secrets.example .env.secrets
cp .env.frontend.secrets.example .env.frontend.secrets
```

The split mirrors the Helm chart, where non-secret configuration lives in the `hopr-config`
ConfigMap and credentials live in the `hopr-secret` Secret:

| File | Contains | k8s counterpart |
| :--- | :--- | :--- |
| `.env` | ScyllaDB contact points/keyspace, Redis node addresses, service ports, `SHORTENER_DOMAIN` | `templates/configmap.yaml` |
| `.env.secrets` | `REDIS_PASSWORD` (empty locally — the Compose Redis cluster starts without `--requirepass`) | `templates/secret.yaml` |
| `.env.frontend.secrets` | `SHORTEN_API_KEY` — the frontend's own credential, kept out of the shared secrets file so the public-facing tier holds nothing else | `templates/secret.yaml`'s `hopr-frontend-secret`, referenced by the single `secretKeyRef` in `templates/frontend.yaml` |

Keep credentials out of `.env` and out of `docker-compose.yml` even when the local value is
empty: how secrets are handled locally is how they end up being handled in production.

Then mint the certificate the gateway serves HTTPS with — it is mounted into the container
from `api-gateway/certs/`, and `docker compose up` fails without it:

```bash
./api-gateway/generate-dev-cert.sh
```

Run it before `docker compose up`: Docker creates a missing `api-gateway/certs` itself (owned
by root on Linux) rather than erroring, and the gateway then crash-loops on "cannot load
certificate" — if that happens, remove the directory and re-run the script.

This certificate is **self-signed and for local development only**: nothing trusts it, so
`curl` needs `-k` and a browser will show a warning you must click through ("Advanced" →
"Proceed"). A real deployment must serve a CA-issued certificate — see
[TLS in README.md](README.md#tls-transport-security) for what terminating TLS at the gateway
means and what would change to issue a real certificate with cert-manager + Let's Encrypt.

---

### Step 3: Start ScyllaDB and Apply the Schema

`shortener-service` and `resolver-service` open a session against the `hopr` keyspace at
boot and fail to start if it does not exist, so the schema must be applied before they come up:

```bash
docker compose up -d scylla-node-1 scylla-node-2 scylla-node-3
./gradlew :db-migration:migrateScylla
```

See `db-migration/README.md` for details.

### Step 4: Start the Rest of the Stack

```bash
docker compose up -d --build
```

This command will:
1. Build local Docker images for `config-server`, `keygen-service`, `shortener-service`, and `resolver-service`.
2. Start the 6 `redis-node` containers (the `scylla-node` containers are already up).
3. Trigger `redis-cluster-init` to assemble the cluster.
4. Launch `api-gateway` and all backend microservices once `scylla-node-1` reports healthy.

---

### Step 5: Verify Container Status

Wait 15–20 seconds for the Spring Boot applications to initialize, then inspect container status:

```bash
docker compose ps
```

All primary services should show `Up` / `running`. The container `hopr-redis-cluster-init` showing `Exited (0)` is expected, as it is a one-time setup job.

To tail logs for all containers:

```bash
docker compose logs -f
```

Or check logs for specific services:

```bash
docker compose logs -f shortener-service
docker compose logs -f resolver-service
docker compose logs -f api-gateway
```

### Step 6: Verify the Services Do Not Run as Root

The four Spring Boot images and the `frontend` image create an unprivileged `appuser` (uid 1001) and switch to it with
`USER`, so a container-breakout vulnerability lands as an unprivileged host user instead of
host root. Confirm it after any Dockerfile change:

```bash
for s in config-server keygen-service resolver-service shortener-service frontend; do
  echo -n "$s: "; docker compose exec -T "$s" id -un
done
```

Every line must print `appuser`. `root` means the `USER` directive was lost.

The images can also be checked without starting the stack:

```bash
docker run --rm --entrypoint id hopr/shortener-service:latest
```

---

## 4. Comprehensive Testing Guide

### Test 1: Config Server Dependency Check

`config-server` has no dashboard UI — it's a plain config-serving REST API. It is a required running dependency: `keygen-service`, `shortener-service`, and `resolver-service` all fetch their configuration from it at startup (`http://config-server:8888` on the Docker network) and will fail to start if it's not healthy. Confirm it's up via:

```bash
docker compose ps config-server
```

It should show `Up` / `running` before the other microservices report healthy.

---

### Test 2: Shorten a URL (Random Key)

> 🔑 **API key required:** `POST /shorten` needs an `X-API-Key` header, or it answers `401
> Unauthorized`. `.env.example` ships `SHORTENER_API_KEY_HASHES` set to the SHA-256 digest of the
> local development key `hopr-local-dev-key`, so a fresh `cp .env.example .env` works with the
> commands below. Only digests are configured — replace the digest (and the key you hand clients)
> for anything beyond local use. `GET /{shortKey}` redirects remain public and unauthenticated.

> ⚠️ **CRITICAL REQUIREMENT:** The request payload must use the JSON key `longUrl` (do **not** use `url`).

Execute the cURL command:

```bash
curl -vk -X POST https://hopr.localhost/shorten \
  -H "X-API-Key: hopr-local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com"}'
```

**Expected Response (HTTP 200 OK):**
```json
{
  "shortUrl": "https://hopr.localhost/WuMBdp2"
}
```
*(The 7-character hash, e.g. `WuMBdp2`, will vary with each call).*

---

### Test 3: Shorten a URL with Custom Alias

Provide an optional `alias` attribute to customize the shortened link:

```bash
curl -vk -X POST https://hopr.localhost/shorten \
  -H "X-API-Key: hopr-local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://github.com/pcaokhai/Hopr", "alias": "my-hopr-repo"}'
```

**Expected Response (HTTP 200 OK):**
```json
{
  "shortUrl": "https://hopr.localhost/my-hopr-repo"
}
```

*Note: Submitting a request with the same `alias` a second time will return `HTTP 409 Conflict` (Alias already taken).*

---

### Test 4: Resolve & Redirect (`GET /{shortKey}`)

Use the key or custom alias returned from the previous step:

```bash
curl -vk https://hopr.localhost/my-hopr-repo
```

**Expected Response:**
```http
< HTTP/1.1 307 Temporary Redirect
< Server: nginx
< Location: https://github.com/pcaokhai/Hopr
```

**Browser Verification:**
Paste `https://hopr.localhost/my-hopr-repo` into your web browser address bar (accept the
self-signed certificate warning the first time). The browser should immediately redirect you to `https://github.com/pcaokhai/Hopr`.

---

### Test 5a: Verify ScyllaDB Schema

The shortener and resolver services read and write the `hopr` keyspace. Inspect the
schema applied in Step 3:

```bash
docker exec hopr-scylla-node-1 cqlsh -e "DESCRIBE KEYSPACE hopr"
docker exec hopr-scylla-node-1 cqlsh -e \
  "SELECT version, description, success FROM hopr.flyway_schema_history"
```

See `db-migration/README.md` for details.

---

### Test 5: Verify ScyllaDB Storage

Inspect the stored rows directly in the `hopr` keyspace:

```bash
docker exec hopr-scylla-node-1 cqlsh -e "SELECT short_key, long_url, alias FROM hopr.urls"
```

**Sample Output:**
```text
 short_key    | long_url                          | alias
--------------+-----------------------------------+--------------
 my-hopr-repo | https://github.com/pcaokhai/Hopr | my-hopr-repo

(1 rows)
```

---

### Test 6: Verify Redis Cluster Caching

Verify cluster state and check cached keys:

```bash
# Check cluster topology and assigned hash slots
docker exec hopr-redis-node-1 redis-cli -c -p 6379 cluster nodes

# Inspect cached keys across cluster
docker exec hopr-redis-node-1 redis-cli -c -p 6379 keys "*"
```

---

### Test 7: Verify Gateway Rate Limiting

The API Gateway enforces a rate limit of 5 requests/sec with a burst allowance of 10 requests. Test this by firing 15 requests in rapid succession:

```bash
for i in {1..15}; do curl -sk -o /dev/null -w "%{http_code}\n" https://hopr.localhost/shorten -H "Content-Type: application/json" -d '{"longUrl": "https://example.com"}'; done
```

**Expected Result:** Initial requests return `200`, followed by `429 Too Many Requests` once the burst threshold is exceeded.

---

## 5. Troubleshooting Common Issues

| Issue | Root Cause | Solution |
| :--- | :--- | :--- |
| **`502 Bad Gateway`** on Nginx | Nginx cached an outdated internal IP for `shortener-service` after a container restart | Restart Nginx to force DNS re-resolution: `docker compose restart api-gateway`. |
| **`500 Internal Server Error`** during URL resolution | Incorrect request body key (`url` instead of `longUrl`), which persisted a `null` destination URL | Always use `{"longUrl": "..."}`. Remove invalid rows: `DELETE FROM hopr.urls WHERE short_key = '...'`. |
| **`NoNodeAvailableException`** / `AllNodesFailedException` on startup | Scylla not yet up, or `spring.cassandra.local-datacenter` does not match the cluster | Wait for the `scylla-node-*` healthchecks, then confirm `SCYLLA_DATACENTER` matches `nodetool status`. |
| **Redis cluster state error after restart** | Stale cluster state / metadata persisted in volume directory | Run `docker compose down -v` or clear files under `./data/redis-*/` before relaunching. |

---

## 6. Teardown & Reset Commands

- **Stop all services (preserve database & cache data):**
  ```bash
  docker compose down
  ```

- **Stop and wipe all data volumes (clean state reset):**
  ```bash
  docker compose down -v
  rm -rf ./data/scylla-*/* ./data/redis-*/*
  ```
