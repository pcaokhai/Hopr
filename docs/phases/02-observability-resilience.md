# Phase 2 — Observability & Resilience

**Goal:** when a dependency slows or dies, know immediately where, and don't let the failure
cascade to callers.

## PR 1 — Circuit breaker + timeout on KeyGenClient (#12)

### What was built
`KeyGenClient`'s reactive `WebClient` call previously `.block()`ed with no timeout inside Spring
MVC's blocking thread pool — a hung `keygen-service` could exhaust Tomcat's thread pool with no
exception ever thrown (cascading failure). Added an 800ms bound plus a Resilience4j circuit
breaker, exposed via Actuator. Narrowed the previously broad `catch (Exception e)` to
distinguish "keygen unavailable" (degrade gracefully) from a genuine unexpected exception (log
distinctly, don't silently swallow).

### Decisions & trade-offs
- **Platform note (not a bug):** Spring Boot 4 dropped the AOP-based Resilience4j starter used
  in most tutorials, so the circuit breaker was wired programmatically instead of via
  annotations.
- No review findings — shipped clean.

### How to test
```bash
./gradlew :shortener-service:test --tests "*KeyGenClient*"

# Manual: simulate a hung keygen-service and confirm the timeout fires
docker compose pause keygen-service
time curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json'
# Should fail fast (~800ms), not hang.
docker compose unpause keygen-service

# Check circuit breaker state via Actuator
curl http://localhost:8081/actuator/health | jq .components.circuitBreakers
```

---

## PR 2 — Distributed tracing + Prometheus (#13)

### What was built
Micrometer Tracing (Brave) + `micrometer-registry-prometheus` wired into `shortener-service`,
`resolver-service`, `keygen-service` (and `config-server`, added in PR 3 below), giving a shared
trace ID across the multi-hop request path. `api-gateway` is nginx, not Spring, so no
Micrometer config applies there.

### Decisions & trade-offs
- **Escalated — accepted, deliberately deferred security gap:** exposing
  `/actuator/prometheus` on the same port nginx already proxies publicly makes the full metric
  set (JVM internals, per-URI timing, circuit-breaker state) curlable by anyone — extending a
  pre-existing exposure (health details were already public), but a real widening. **Captain
  decided:** ship as-is; lockdown deferred to Phase 3 rather than scope-creeping this PR.
- **Near-miss, self-corrected:** an initial fix-round instruction to remove an
  "apparently-duplicate" `micrometer-tracing-bridge-brave` dependency was **wrong** — the
  fix-reviewer verified against actual Gradle module metadata that the bridge is *not*
  transitive, and removing it would have silently left tracing on Boot's no-op tracer. The
  deviation was accepted and documented in `AGENTS.md` for future readers.

### How to test
```bash
# Confirm Prometheus scraping works
curl http://localhost:8081/actuator/prometheus | head -20

# Confirm a single request produces a linked trace across services
curl -v -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' 2>&1 | grep -i traceparent
docker compose logs shortener-service | grep <traceId>
docker compose logs keygen-service | grep <traceId>
# Same trace ID should appear in both services' logs for one request.
```

---

## PR 3 — Graceful shutdown (#14)

### What was built
`server.shutdown=graceful` + a 20-second drain window across all four JVM services (including
`config-server`), aligned with k8s Deployments configured `maxUnavailable: 0`, a 30s
`terminationGracePeriodSeconds`, and a 5s `preStop` sleep hook; matching `stop_grace_period` in
docker-compose. `api-gateway` (nginx) needs no equivalent. A new integration test proves an
in-flight request completes rather than being dropped when the app context closes mid-request.

### Decisions & trade-offs
- **Bug — test didn't guard the shipped config:** the test passed `server.shutdown=graceful`
  as inline test-only properties instead of reading the real `config-repo/application.yml`
  values, so deleting the shipped config entirely would have left the test green. **Fixed:**
  point the test at the real shipped values.
- **Bug — `config-server` missed from the stated scope:** the brief said "across the backend
  services," but `config-server` (not a config *client* of itself) inherited neither the
  property nor a matching grace period — a redeploy could cut an in-flight config fetch from a
  *starting* pod. **Fixed:** completed the already-stated intent, added to `config-server` too.
- **Bug — inconsistent application:** a follow-up pass caught `config-server`'s k8s manifest got
  the grace period but not the matching `preStop` hook the other three services already carry,
  and the new test covered the app services' config copy but not `config-server`'s own separate
  copy. **Fixed:** applied the identical already-accepted pattern to close both gaps.

### How to test
```bash
./gradlew :shortener-service:test --tests "*GracefulShutdown*"

# Manual: fire a slow request, then trigger shutdown, confirm it completes
curl http://localhost:8081/shorten & sleep 0.1; docker compose stop -t 25 shortener-service
# The in-flight request above should complete successfully, not be dropped.
```
