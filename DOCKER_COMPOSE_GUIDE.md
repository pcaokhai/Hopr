# Hopr: Docker Compose Setup & Testing Guide

This guide provides step-by-step instructions to build, run, and test the entire Hopr URL shortener ecosystem (including Config Server, API Gateway, Microservices, MongoDB, and Redis Cluster) using **Docker Compose** in a local environment.

---

## 1. Architecture & Services Overview

All services run inside a dedicated Docker bridge network (`hopr_default`):

| Service Name | Container Name | Port (Host : Container) | Description |
| :--- | :--- | :--- | :--- |
| **`api-gateway`** | `hopr-api-gateway` | `80:80` | Nginx reverse proxy routing `/shorten` to `shortener-service` and `/{alias}` to `resolver-service`. Rate limited (5 req/s, burst 10). |
| **`config-server`** | `hopr-config-server` | - | Spring Cloud Config Server serving centralized configuration to the microservices (internal only, no host port published). |
| **`keygen-service`** | `hopr-keygen-service` | `8081:8081` | Generates unique random keys for shortened URLs. |
| **`shortener-service`** | `hopr-shortener-service` | `8080:8080` | Handles URL shortening requests, persists to MongoDB, caches in Redis, interacts with KeyGen. |
| **`resolver-service`** | `hopr-resolver-service` | `8083:8083` | Resolves short keys, checks Redis cache (falls back to Mongo), returns HTTP 307 redirect. |
| **`mongodb`** | `hopr-mongodb` | `27017:27017` | MongoDB 7.0 (database: `Hopr`, user: `root`, password: `password`). Persistent storage for URL mappings. |
| **`scylla-node-1` .. `3`** | `hopr-scylla-node-1..3` | `9042..9044:9042` | 3-node ScyllaDB cluster (keyspace `hopr`, RF 3). Schema applied by `db-migration`; not yet consumed by any service. |
| **`redis-node-1` .. `6`** | `hopr-redis-node-1..6` | `7001..7006:6379` | 6-node Redis Cluster (3 masters, 3 replicas) for distributed caching. |
| **`redis-cluster-init`** | `hopr-redis-cluster-init` | - | One-shot initialization container to cluster the 6 Redis nodes on startup. |

Persistent data for MongoDB, the 3 ScyllaDB nodes and all 6 Redis nodes is bind-mounted to the `./data/` directory at the project root.

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

### Step 2: Verify Configuration (`.env`)

The `.env` file at the root contains pre-configured settings for local containerized communication:
- `MONGO_URI=mongodb://root:password@mongodb:27017/Hopr?authSource=admin`
- `REDIS_NODE_1=redis-node-1:6379` ... `REDIS_NODE_6=redis-node-6:6379`
- `SHORTENER_DOMAIN=http://hopr.localhost/`

Ensure the ports and credentials match your intended local setup.

---

### Step 3: Start the Stack with Docker Compose

Run the following command in the project root:

```bash
docker compose up -d --build
```

This command will:
1. Build local Docker images for `config-server`, `keygen-service`, `shortener-service`, and `resolver-service`.
2. Start `mongodb` and the 6 `redis-node` containers.
3. Trigger `redis-cluster-init` to assemble the cluster.
4. Launch `api-gateway` and all backend microservices with proper dependency ordering.

---

### Step 4: Verify Container Status

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

> ⚠️ **CRITICAL REQUIREMENT:** The request payload must use the JSON key `longUrl` (do **not** use `url`).

Execute the cURL command:

```bash
curl -v -X POST http://hopr.localhost/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com"}'
```

**Expected Response (HTTP 200 OK):**
```json
{
  "shortUrl": "http://hopr.localhost/WuMBdp2"
}
```
*(The 7-character hash, e.g. `WuMBdp2`, will vary with each call).*

---

### Test 3: Shorten a URL with Custom Alias

Provide an optional `alias` attribute to customize the shortened link:

```bash
curl -v -X POST http://hopr.localhost/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://github.com/pcaokhai/Hopr", "alias": "my-hopr-repo"}'
```

**Expected Response (HTTP 200 OK):**
```json
{
  "shortUrl": "http://hopr.localhost/my-hopr-repo"
}
```

*Note: Submitting a request with the same `alias` a second time will return `HTTP 409 Conflict` (Alias already taken).*

---

### Test 4: Resolve & Redirect (`GET /{shortKey}`)

Use the key or custom alias returned from the previous step:

```bash
curl -v http://hopr.localhost/my-hopr-repo
```

**Expected Response:**
```http
< HTTP/1.1 307 Temporary Redirect
< Server: nginx
< Location: https://github.com/pcaokhai/Hopr
```

**Browser Verification:**
Paste `http://hopr.localhost/my-hopr-repo` into your web browser address bar. The browser should immediately redirect you to `https://github.com/pcaokhai/Hopr`.

---

### Test 5a: Verify ScyllaDB Schema

The ScyllaDB cluster runs alongside MongoDB but does not serve any service yet. Apply the
Flyway migrations and inspect the result:

```bash
docker compose up -d scylla-node-1 scylla-node-2 scylla-node-3
./gradlew :db-migration:migrateScylla
docker exec hopr-scylla-node-1 cqlsh -e "DESCRIBE KEYSPACE hopr"
docker exec hopr-scylla-node-1 cqlsh -e \
  "SELECT version, description, success FROM hopr.flyway_schema_history"
```

See `db-migration/README.md` for details.

---

### Test 5: Verify MongoDB Storage

Inspect the stored records directly within the MongoDB database:

```bash
docker exec hopr-mongodb mongosh -u root -p password --eval 'db.getSiblingDB("Hopr").urls.find().toArray()'
```

**Sample Output:**
```javascript
[
  {
    _id: 'my-hopr-repo',
    longUrl: 'https://github.com/pcaokhai/Hopr',
    alias: 'my-hopr-repo',
    _class: 'com.pcaokhai.common.url.model.UrlMapping'
  }
]
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
for i in {1..15}; do curl -s -o /dev/null -w "%{http_code}\n" http://hopr.localhost/shorten -H "Content-Type: application/json" -d '{"longUrl": "https://example.com"}'; done
```

**Expected Result:** Initial requests return `200`, followed by `429 Too Many Requests` once the burst threshold is exceeded.

---

## 5. Troubleshooting Common Issues

| Issue | Root Cause | Solution |
| :--- | :--- | :--- |
| **`502 Bad Gateway`** on Nginx | Nginx cached an outdated internal IP for `shortener-service` after a container restart | Restart Nginx to force DNS re-resolution: `docker compose restart api-gateway`. |
| **`500 Internal Server Error`** during URL resolution | Incorrect request body key (`url` instead of `longUrl`), which persisted a `null` destination URL | Always use `{"longUrl": "..."}`. Remove invalid records from MongoDB: `db.urls.deleteOne({longUrl: null})`. |
| **`MongoTimeoutException`** on startup | Spring Boot 4 requires `spring.mongodb.uri` | Ensure `application.yml` contains both `spring.mongodb.uri` and `spring.data.mongodb.uri`. |
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
  rm -rf ./data/mongodb/* ./data/redis-*/*
  ```
