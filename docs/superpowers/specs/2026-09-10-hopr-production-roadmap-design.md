# Hopr — Learning-Driven Production Roadmap

> Companion to `docs/hopr-production-review.md`. That document is the architecture
> review (what's wrong, why, and the original 6-phase roadmap). This document
> re-scopes that roadmap for a **learning-paced, delegated-implementation**
> workflow: the captain reviews and learns from each PR rather than racing to
> finish the app.

## Working model

- **Delegation:** every phase/concept is implemented by a crewmate (an autonomous
  worker), not by the captain directly. The captain's role is to read each PR's
  diff and concept write-up to learn the pattern it demonstrates.
- **Granularity:** one PR per concept, not one PR per phase. Bundling multiple
  concepts into one diff makes it harder to connect a diff to a specific lesson.
- **Sequencing:** phases execute in dependency order — correctness before
  observability, observability before security, security before scaling out,
  scaling before event-driven architecture, event-driven before advanced k8s
  patterns. This mirrors how a real team would sequence the work, not just an
  arbitrary topic list.
- **Cadence:** once a PR merges, the next concept in the queue dispatches
  automatically. No need to re-approve the whole roadmap at every step — only
  intervene to skip, reorder, or pause a specific item.
- **PR shape:** every PR's description must include a concept write-up — what was
  wrong, why, and the trade-offs of the fix — written for someone learning the
  pattern, not a terse changelog. This is the primary learning artifact per PR.
- **Schema changes:** all ScyllaDB schema changes (from Phase 1 onward) go
  through **Flyway** versioned migrations (using the Cassandra/Scylla community
  plugin, `flyway-community-db-support`, since Flyway has no native CQL
  support), not hand-run `cqlsh` scripts. Every future schema change — adding
  `expires_at`, owner/tenant columns, etc. — becomes a new `V<n>__description.cql`
  migration file rather than manual DDL. This is itself a concept worth
  learning: schema-as-code applies the same discipline to a wide-column store
  as to a relational one.

## Phase 1 — Correct data foundation

**Goal:** a short key always points to exactly one long URL, even under
concurrent requests and when `keygen-service` is scaled horizontally.

Four concept-PRs, in this order:

### 1. Worker-ID fix
Replace the IP/hostname-derived Snowflake worker ID in `DefaultNodeInfoProvider`
with a coordinated allocation scheme (Redis `INCR` + lease TTL on pod startup,
or a Kubernetes `StatefulSet` ordinal). Self-contained to `keygen-service`;
does not depend on the database migration below, so it can ship first as a
smaller, standalone win.

*Concept: distributed ID generation without a coordinator vs. with one — the
trade-off between "instances infer their own ID" (fast, no dependency, but
collision-prone) and "a coordinator allocates IDs" (safe, but adds a
must-be-available dependency).*

### 2. ScyllaDB schema + infra
Stand up a 3-node ScyllaDB cluster (matching the HA pattern already used for
Redis Cluster) and define the keyspace and three tables from the review's
schema (`urls`, `url_click_counts` as a counter table, `url_click_events`
partitioned by `(short_key, day)`) as Flyway migrations. No application code
changes yet — infrastructure and schema only, validated via `cqlsh` or a
migration-runner smoke test.

*Concept: query-first data modeling (design tables around the queries that
will run, not around a natural object shape), replication factor and quorum,
partition-key design to avoid hot partitions, and schema-as-code via Flyway.*

### 3. Repository swap
Cut `shortener-service` and `resolver-service` over from
`spring-boot-starter-data-mongodb` to `spring-boot-starter-data-cassandra`:
update `UrlMapping` entity annotations (`@Table`/`@PrimaryKey` instead of
`@Document`/`@Id`), change `UrlRepository` to extend `CassandraRepository`,
update Config Server properties (`spring.cassandra.contact-points` /
`keyspace-name`), and swap `BaseIntegrationTest`'s `MongoDBContainer` for a
Cassandra/Scylla Testcontainers module. At the end of this PR the app runs
against ScyllaDB with a plain `INSERT` — the original TOCTOU race still
exists, now on the new infrastructure, and gets closed by the next PR.

*Concept: adapting application code to a structurally different (wide-column)
store behind a similar repository abstraction — what changes and what
doesn't when the underlying data model assumptions shift.*

### 4. LWT uniqueness fix
Replace the check-then-save pattern (`AliasAvailabilityValidator.findById`
followed by a separate `save()`) with a single
`INSERT ... IF NOT EXISTS` lightweight transaction. Map a failed
conditional apply to an HTTP `409`. Select consistency levels per operation
as documented in the review: `SERIAL`/`LOCAL_SERIAL` for alias writes (LWT
requires it), `LOCAL_QUORUM` for auto-generated keys (no LWT needed once the
worker-ID fix guarantees uniqueness), `LOCAL_ONE` for cache-miss reads.
Include a test that reproduces the original race (two concurrent requests for
the same alias) and demonstrates it is now closed.

*Concept: lightweight transactions (Paxos-backed conditional writes)
replacing check-then-act for uniqueness — why the uniqueness invariant must
live at the storage layer, not the application layer, in a distributed
system.*

## Phases 2–6 (unchanged from the original review, re-scoped as concept-PRs when reached)

These remain as scoped in `docs/hopr-production-review.md` section 6, broken
into individual concept-PRs using the same one-concept-per-PR approach when
each phase is reached:

- **Phase 2 — Observability & resilience:** distributed tracing (Micrometer +
  Prometheus), circuit breaker/timeout around `KeyGenClient` (Resilience4j),
  graceful shutdown.
- **Phase 3 — Security hardening:** input validation on `/shorten`, API key
  auth, secret management (Docker/k8s secrets instead of hardcoded
  credentials), non-root containers, TLS termination.
- **Phase 4 — Data model expansion & testing:** TTL/`expires_at` (as a Flyway
  migration), list/update/delete endpoints for created links, parameterized
  Helm replicas + resource requests/limits + HPA, contract and load testing.
- **Phase 5 — Event-driven architecture:** outbox pattern for the
  Scylla-write + cache-prime step, `UrlCreated`/`UrlClicked` events via
  Kafka, a separate consumer populating the click-count/event tables.
- **Phase 6 — Kubernetes production patterns:** progressive delivery
  (canary/blue-green), Pod Disruption Budgets, multi-AZ awareness, API
  versioning, basic multi-tenancy via `owner_id`.

## Out of scope

- A Mongo → Scylla data backfill script: not worth a dedicated lesson here
  since this is a learning project with no real production data to migrate.
  Revisit only if the captain wants data-migration tooling as its own future
  concept-PR.
