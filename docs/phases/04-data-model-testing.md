# Phase 4 — Data Model Expansion & Testing

**Goal:** turn "horizontal scalability" from a README claim into working configuration, give
links a manageable lifecycle, and actually measure the performance claims.

## PR 1 — TTL/expiration for URL mappings (#21)

### What was built
An optional `expiresInSeconds` field on `POST /shorten`. When set, the LWT insert (from Phase
1) adds a ScyllaDB `USING TTL <seconds>` clause, so the database itself removes the row —
native storage-layer expiry, not an application-level cron/scan job. `expires_at` stays
populated for read-visibility even though TTL is what actually removes the row.

### Decisions & trade-offs
- Clean pass, no review findings.
- **Named limitation (by design, not a bug):** native TTL deletes the row entirely — there is
  no "expired but recorded" audit state without a separate mechanism. Acceptable here since
  Phase 5's click-analytics tables serve a different purpose (event history, not mapping state).

### How to test
```bash
./gradlew :shortener-service:test --tests "*Ttl*" :resolver-service:test --tests "*Ttl*"

# Manual: create a link with a short TTL and confirm it stops resolving
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com","expiresInSeconds":3}' \
  -H 'Content-Type: application/json' -H 'X-API-Key: <key>'
sleep 4
curl -I http://localhost/<shortKey>   # expect 404, not 307

# Confirm a link without an expiration is unaffected
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' \
  -H 'Content-Type: application/json' -H 'X-API-Key: <key>'
```

---

## PR 2 — Link management endpoints (#22)

### What was built
`GET /links`, `GET /links/{shortKey}`, `PATCH /links/{shortKey}`, `DELETE /links/{shortKey}` in
`shortener-service`, all requiring the same API key `/shorten` already requires. Real per-owner
scoping was deliberately deferred to Phase 6 (only one shared key existed at this point) — any
caller with a valid key can manage any link at this stage.

### Decisions & trade-offs
- **Named limitation (by design):** listing is unscoped ("all links in the system") since there
  was no owner identity yet to filter by — this is exactly what Phase 6 PR 2 later fixes.
- One test-flake fixed mid-pipeline (an update test NPE'd on a stale mock) — minor, self-caught
  by the fix round.

### How to test
```bash
./gradlew :shortener-service:test --tests "*LinkManagement*"

# Manual CRUD cycle
KEY='X-API-Key: <key>'
SHORT=$(curl -s -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' -H "$KEY" | jq -r .shortKey)
curl -H "$KEY" http://localhost/links/$SHORT          # GET
curl -H "$KEY" http://localhost/links                 # list
curl -X PATCH -H "$KEY" -d '{"longUrl":"https://example.org"}' -H 'Content-Type: application/json' http://localhost/links/$SHORT
curl -X DELETE -H "$KEY" http://localhost/links/$SHORT
curl -I http://localhost/$SHORT   # expect 404 after delete
```

---

## PR 3 — Helm autoscaling (#23)

### What was built
Parameterized `replicas` via `values.yaml` for every backend Deployment (previously hardcoded
`replicas: 1` directly in templates, which would have silently fought any HPA added later).
Added CPU/memory `resources.requests/limits`. Added a `HorizontalPodAutoscaler` for
`shortener-service` and `resolver-service` (the two hot-path services), scaling on CPU. The
missing `metrics-server` dependency required for HPA to actually function was honestly
documented rather than glossed over.

### Decisions & trade-offs
- Clean pass, no review findings.

### How to test
```bash
# Chart-render test proving parameterization actually works (no live cluster needed)
./gradlew :chart-test  # or: bash scripts/chart-template-test.sh

# Confirm values override actually changes rendered output
helm template k8s/hopr-chart --set shortenerService.replicas=3 | grep -A2 "kind: Deployment" | grep replicas

# Live: confirm HPA is present and (if metrics-server installed) functioning
kubectl get hpa
kubectl describe hpa shortener-service
```

---

## PR 4 — Contract test + k6 load test (#24)

### What was built
A contract test for the `shortener-service` ↔ `keygen-service` boundary (shared JSON fixture in
`docs/contracts/`), so a breaking shape change on either side fails a test before reaching
production. A k6 load test against the resolver's redirect path, measuring real latency instead
of trusting the README's unverified "sub-millisecond" claim.

### Decisions & trade-offs
- **Honest correction, not a bug fix:** measured **p50=409μs, p95=886μs, p99=1.66ms**. The
  README's "sub-millisecond" claim held at p50/p95 but broke at p99 — the README was corrected
  to state the measured numbers and the conditions (load level, environment) they were verified
  under, rather than leaving a stale, partially-false marketing claim.

### How to test
```bash
# Contract test
./gradlew :shortener-service:test --tests "*Contract*"

# Load test (requires the stack running via docker compose)
docker compose up -d
./scripts/load-test-resolver.sh
# Reports p50/p95/p99; compare against docs/phases/04-data-model-testing.md's recorded numbers
# to see if performance has regressed since this measurement.
```
