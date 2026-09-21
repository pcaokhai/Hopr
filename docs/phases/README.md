# Hopr Production Roadmap — Documentation Index

This folder documents the full learning-paced production roadmap executed against Hopr,
covering all six phases from `docs/superpowers/specs/2026-09-10-hopr-production-roadmap-design.md`.
Each phase file covers, for every PR in that phase:

- **What was built** — the feature/fix itself.
- **Decisions & trade-offs** — every choice made along the way, including bugs the automated
  review caught before merge and why each was fixed (or deliberately left) the way it was.
- **How to test it** — concrete commands to exercise and verify the feature yourself.

## Phases

1. [Phase 1 — Correct Data Foundation](01-data-foundation.md) — worker-ID fix, ScyllaDB
   migration, LWT uniqueness fix. PRs #8–#11.
2. [Phase 2 — Observability & Resilience](02-observability-resilience.md) — circuit breaker,
   distributed tracing, graceful shutdown. PRs #12–#14.
3. [Phase 3 — Security Hardening](03-security-hardening.md) — input validation, secrets/non-root
   containers, API key auth, TLS termination. PRs #15–#16, #19–#20 (plus #17/#18 support work).
4. [Phase 4 — Data Model Expansion & Testing](04-data-model-testing.md) — TTL/expiration, link
   management endpoints, Helm autoscaling, contract/load testing. PRs #21–#24.
5. [Phase 5 — Event-Driven Architecture](05-event-driven-architecture.md) — outbox pattern,
   Kafka event publishing, click-analytics consumer. PRs #25–#27.
6. [Phase 6 — Kubernetes Production Patterns](06-k8s-production-patterns.md) — Pod Disruption
   Budgets/multi-AZ, API versioning + multi-tenancy, progressive delivery. PRs #28–#30.

## Related documents

- `docs/hopr-production-review.md` — the original architecture review that motivated this
  entire roadmap.
- `docs/superpowers/specs/2026-09-10-hopr-production-roadmap-design.md` — the roadmap design
  spec (working model, phase breakdown) approved before implementation started.
- `docs/superpowers/specs/hopr-implementation-log.md` — an earlier, narrower log covering
  Phases 1–3 only; this `docs/phases/` folder supersedes it with full coverage (Phases 1–6)
  and adds testing instructions.
