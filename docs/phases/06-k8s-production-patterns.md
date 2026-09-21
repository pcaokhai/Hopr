# Phase 6 — Kubernetes Production Patterns

**Goal:** protect against voluntary disruptions, close the last "single shared key" gap with
real multi-tenancy, and roll out changes safely instead of all-at-once.

## PR 1 — Pod Disruption Budgets + multi-AZ awareness (#28)

### What was built
A `PodDisruptionBudget` for each backend Deployment with more than one replica (matching Phase
4's HPA-managed services), plus `topologySpreadConstraints` keyed on
`topology.kubernetes.io/zone` with `whenUnsatisfiable: ScheduleAnyway` (a soft preference, since
`DoNotSchedule` would make pods unschedulable on this repo's single-node local `kind` cluster).

### Decisions & trade-offs
- **Named limitation (by design):** this repo's local `kind` cluster is single-node with no real
  availability zones. The configuration is correct for a real multi-zone cluster, but local
  testing can only prove it's syntactically valid and doesn't break deployment — not that pods
  actually spread across zones. Documented explicitly in `k8s/README.md` rather than faking
  zone labels to simulate a result that wasn't actually observed.
- **Simplification:** an unnecessary `topologySpread.enabled` toggle was flagged (the intent
  asked for correct config, not a switch for it — `ScheduleAnyway` already makes it safe
  unconditionally). **Fixed:** removed, render unconditionally.

### How to test
```bash
# Chart-render test (what's actually verifiable without a multi-zone cluster)
bash scripts/chart-template-test.sh

# Live: confirm the PDB exists and would block over-eager disruption
kubectl get pdb
kubectl describe pdb shortener-service

# NOTE: actual zone-spreading behavior cannot be observed on this repo's single-node
# local kind cluster - it requires a real multi-zone cluster (e.g. managed EKS/GKE/AKS).
```

---

## PR 2 — API versioning + basic multi-tenancy (#29)

### What was built
Extended the API-key mechanism (Phase 3) from a flat list of equivalent keys to a map of
key-hash → owner-id, so each key identifies a distinct owner. `urls.owner_id` (unused since
Phase 1) now gets populated on creation. Phase 4's link-management endpoints now scope to the
caller's own `owner_id`: `GET /links` returns only the caller's links, and
`GET/PATCH/DELETE /links/{shortKey}` return `404` (not `403`, to avoid leaking existence of
another owner's link) for a link belonging to a different owner. Routes versioned under `/v1`.

### Decisions & trade-offs
- **Design choice:** "basic" multi-tenancy exactly as the roadmap named it — multiple
  pre-configured keys each mapped to an owner id, not a full user-registration/OAuth system,
  appropriate for this project's actual scale.
- **Design choice:** URL path versioning (`/v1/shorten`) over header-based versioning — simplest
  and most explicit for a learning project. The public redirect path (`GET /{shortKey}`) was
  deliberately left unversioned, since short links must stay stable and shouldn't embed API
  version churn.
- **Escalated — pre-existing links would become permanently orphaned:** rows created before this
  PR have `owner_id = null`, and the new strict `owner_id` filtering would make them invisible
  and unmanageable forever (still resolving fine, just unmanageable). **Captain decided:**
  backfill legacy rows to a designated default owner rather than orphaning them.
- **Bug — the backfill implementation regressed Phase 4's HPA design:** the backfill ran as an
  `ApplicationRunner` enabled by default, doing an unpaged full-table scan on every pod startup
  — meaning every new pod the HPA adds under load would rescan the entire table before serving a
  request (slowing scale-out exactly when it matters), and a scan failure would crash-loop the
  pod (a link-visibility fix turning into a write-path outage). **Fixed:** defaulted the flag to
  off, documented as a manual one-time operator step run once after this PR deploys, not
  something every replica repeats on boot.
- **Minor cleanup:** a dead static demo client still pointed at the removed unversioned route
  (updated for parity); an unnecessary second dev key/owner pair was removed (the real
  integration test already proves scoping with its own test-only keys).

### How to test
```bash
./gradlew :shortener-service:test --tests "*OwnerScoping*"

# Manual: two keys, two owners, verify isolation
KEY_A='X-API-Key: <owner-a-key>'
KEY_B='X-API-Key: <owner-b-key>'
SHORT=$(curl -s -X POST http://localhost/v1/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' -H "$KEY_A" | jq -r .shortKey)
curl -H "$KEY_B" http://localhost/v1/links/$SHORT   # expect 404, not 403
curl -H "$KEY_A" http://localhost/v1/links/$SHORT   # expect 200
curl -I http://localhost/$SHORT                      # public redirect still works, no key needed

# One-time legacy backfill (run manually once after deploying this PR, NOT on every pod boot)
docker compose exec shortener-service java -jar app.jar --run-legacy-owner-backfill
```

---

## PR 3 — Progressive delivery (#30, final PR of the roadmap)

### What was built
Argo Rollouts installed; `shortener-service` and `resolver-service` converted from `Deployment`
to `Rollout` resources with a canary strategy (small initial traffic percentage, a pause step,
progressive steps to 100%). Uses Argo Rollouts' basic replica-ratio canary (no service mesh
required, since this repo has none) rather than true traffic-percentage splitting. Phase 4's
HPA retargeted to the `Rollout` resource (required by Argo Rollouts). Live-verified against the
local `kind` cluster — step/pause/abort/promote mechanics work identically regardless of node
count, unlike Phase 6 PR 1's multi-AZ work.

### Decisions & trade-offs
- **Design choice:** Argo Rollouts over Flagger — Flagger requires a service mesh or
  ingress-controller integration this repo doesn't have; adding one just for this PR would be
  over-engineering.
- **Design choice:** a manually-gated pause rather than an automated Prometheus-based
  `AnalysisTemplate` — an honest, simpler starting point; automated analysis based on Phase 2's
  metrics is named as a natural future step rather than built here.
- **Escalated — the one-time cutover causes full downtime:** converting `Deployment` → `Rollout`
  on an *existing* cluster deletes the old Deployment (and every running pod) before the Rollout
  creates new ones, and Argo Rollouts skips the canary on initial creation — so the very upgrade
  that introduces progressive delivery briefly takes both hot-path services fully down for a
  cold start. **Captain decided:** document this clearly as an expected one-time event (not
  steady-state behavior) rather than build a zero-downtime adoption mechanism for a single
  historical migration moment.

### How to test
```bash
# Chart-render test proving the Rollout has the expected canary steps
bash scripts/chart-template-test.sh

# Live: trigger and observe a rollout
kubectl argo rollouts get rollout shortener-service --watch
# In another terminal, trigger a new rollout (e.g. bump the image tag) and watch it progress
# through canary steps, pausing where configured.

kubectl argo rollouts promote shortener-service   # manually advance past a pause
kubectl argo rollouts abort shortener-service     # roll back if something looks wrong
```

---

## Roadmap complete

This closes the entire six-phase learning roadmap: **30 merged PRs**, taking Hopr from a
local-only demo through a genuinely production-shaped system across data correctness,
observability, security, testing discipline, event-driven architecture, and Kubernetes
operational patterns.
