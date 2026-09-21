# Phase 1 — Correct Data Foundation

**Goal:** a short key always points to exactly one long URL, even under concurrent requests and
when `keygen-service` is scaled horizontally.

## PR 1 — Worker-ID fix (#8)

### What was built
`DefaultNodeInfoProvider` derived its Snowflake `datacenterId`/`machineId` from
`hostname.hashCode() % 32` and the last byte of the container's IPv4 address. Container IPs are
often sequential within a subnet, so two `keygen-service` replicas could silently collide on the
same worker ID, producing duplicate Snowflake IDs (and duplicate short keys) under concurrent
traffic. Replaced with `WorkerIdLeaseAllocator` + `RedisNodeInfoProvider`: each instance
atomically claims a worker ID from Redis on startup with a TTL, so a crashed instance's ID is
eventually reclaimed.

### Decisions & trade-offs
- **Redis lease vs. StatefulSet ordinal:** chose Redis lease because Redis Cluster is already a
  dependency in this stack (no new infrastructure), and it works under a plain Deployment, not
  just a StatefulSet. Trade-off: adds a runtime dependency on Redis at `keygen-service` startup,
  versus a StatefulSet ordinal's zero-coordination determinism (which only works under
  StatefulSet deployment).
- No review findings — shipped clean on the first pass.

### How to test
```bash
# Unit/integration tests covering the lease allocator
./gradlew :keygen-service:test

# Manual: scale keygen-service to 3+ replicas and confirm each gets a distinct worker ID
docker compose up -d --scale keygen-service=3
docker compose logs keygen-service | grep -i "worker.*id"
# Each instance's log line should show a different worker/datacenter id, no collisions.
```

---

## PR 2 — ScyllaDB schema + infra (#9)

### What was built
A 3-node ScyllaDB cluster (docker-compose + k8s StatefulSet), matching the HA pattern already
used for Redis Cluster, with the keyspace and three tables (`urls`, `url_click_counts` as a
counter table, `url_click_events` partitioned by `(short_key, day)`) defined as **Flyway**
versioned CQL migrations (`V1`–`V4`) instead of hand-run `cqlsh` scripts.

### Decisions & trade-offs
- **Bug — developer mode on the "production" k8s chart:** initial implementation left
  `--developer-mode 1`/`--overprovisioned 1` on the StatefulSet, silently disabling Scylla's
  production durability checks on infrastructure built at RF=3 specifically for durability.
  **Fixed:** removed from the k8s chart only; docker-compose keeps them (documented laptop-only).
- **Bug — dead config key:** `values.yaml`'s `scylladb.keyspace` was never read (the real
  keyspace name lives in the Flyway migration). **Fixed:** removed.
- **Escalated — production-mode boot failure risk:** removing developer mode made Scylla's
  environment checks strict, but the chart's PVC had no pinned StorageClass/`io_properties`,
  risking a boot failure or timeout on clusters without XFS storage. **Captain decided:**
  document the requirement instead of hard-pinning a StorageClass, keeping the chart deployable
  on generic/local clusters for a learning project.

### How to test
```bash
# Verify the cluster comes up healthy
docker compose up -d scylla-node-1 scylla-node-2 scylla-node-3
docker compose ps  # all three should show "healthy"

# Verify the schema migrated correctly
docker compose exec scylla-node-1 cqlsh -e "DESCRIBE KEYSPACE hopr;"
# Should list urls, url_click_counts, url_click_events

# Run the Flyway migration test module directly
./gradlew :db-migration:test
```

---

## PR 3 — Repository swap to Cassandra (#10)

### What was built
Cut `shortener-service` and `resolver-service` from `spring-boot-starter-data-mongodb` to
`spring-boot-starter-data-cassandra` against the Phase 1 cluster — `UrlMapping` re-annotated,
`UrlRepository` now `CassandraRepository`, MongoDB removed entirely, Testcontainers swapped to a
real Scylla node. Deliberately kept a plain `INSERT` (the uniqueness fix is PR 4) so the infra
cutover and correctness fix could be reviewed independently.

### Decisions & trade-offs
- **Incident (not a bug):** the implementing session's machine slept mid-response overnight,
  leaving 24 files of real progress uncommitted. Recovered by resuming the same worker in place
  — no work lost. Later hit its own account rate limit; resumed automatically once cleared.
- **Bug — schema-before-app-start race:** compose/k8s started app services before the Flyway
  migration necessarily ran, risking a crash on fresh checkout. **Fixed:** `condition:
  service_healthy` in compose, migration-gated app rollout in k8s (`urlServices.enabled` flag).
- **Bug — duplicated test schema:** integration tests hand-mirrored the keyspace/table DDL
  instead of pointing at the real Flyway files, so a future schema change wouldn't be caught by
  the test meant to catch it. **Fixed:** point Testcontainers at the real migration files.
- **Bug — dead test dependency (×2):** `testcontainers-junit-jupiter` added to both services but
  unused (this codebase uses a singleton container pattern). **Fixed:** removed from both.
- **Escalated — deploy-script idempotency regression:** the schema-race fix made `deploy.sh`
  unconditionally disable-then-re-enable the URL services on every run, breaking documented
  re-run idempotency (forced ~1 minute of `502`s on every redeploy). **Captain decided:** skip
  the disable pass on repeat runs.
- **Escalated — flag-based sequencing had a residual defect:** a follow-up review flagged the
  `urlServices.enabled` machinery itself had a boot-ordering bug, recommending backing it out
  entirely (same-theme accretion risk). **Captain decided:** keep and harden the flag-based
  approach rather than back it out.

### How to test
```bash
# Full backend suite against real Scylla
./gradlew :shortener-service:test :resolver-service:test

# End-to-end: shorten then resolve
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json'
curl -I http://localhost/<shortKey>   # expect 307 to https://example.com

# Confirm Mongo is gone
grep -ri mongo docker-compose.yml   # should return nothing
```

---

## PR 4 — LWT uniqueness fix (#11)

### What was built
Replaced check-then-save (`AliasAvailabilityValidator.findById` + separate save) with a single
`INSERT ... IF NOT EXISTS` lightweight transaction (Paxos-backed) for the custom-alias path, at
`SERIAL` consistency, mapping a failed conditional apply to `409 Conflict`. A test reproducing
the original race (two concurrent requests for the same alias) proved the old bug
(`[200,200]`, silent overwrite) and its closure (`[200,409]`).

### Decisions & trade-offs
- **Bug — brief's own scoping was wrong:** the original brief assumed the generated-key path
  (Snowflake IDs, unique per PR 1's fix) didn't need the LWT. Review caught that generated keys
  (7-char base62) and custom aliases (3–20 char alphanumeric) share the same key-space, so a
  generated key could still collide with a previously *claimed alias* and silently overwrite it
  — the exact bug this PR exists to close, via the other write path. **Fixed:** route the
  generated-key path through the same atomic write, retrying with a new key on collision
  (bounded attempts) rather than surfacing an error to the caller.
- **Considered and skipped:** the fix left `KeyGenResolver` as a bare passthrough with no logic
  of its own. Flagged as optional cleanup; left as-is to keep the diff focused.

### How to test
```bash
./gradlew :shortener-service:test --tests "*AliasRace*"

# Manual concurrency check: fire two requests for the same alias at once
for i in 1 2; do
  curl -s -o /tmp/out$i -w "%{http_code}\n" -X POST http://localhost/shorten \
    -d '{"longUrl":"https://example.com/'$i'","alias":"race-test"}' \
    -H 'Content-Type: application/json' &
done; wait
# Expect exactly one 200 and one 409 - never two 200s.
```
