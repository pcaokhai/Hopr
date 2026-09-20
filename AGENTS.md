## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.

## Frontend

`frontend/` is a Next.js (App Router) + TypeScript + Tailwind + shadcn/ui + Zustand app. See `frontend/README.md`
for how to run it and exactly which screens are wired to the real `/shorten` API vs. backed by mock data
(the dashboard isn't wired to the `/links` endpoints below yet, and there is still no analytics or
auth endpoint). shadcn/ui here uses the Base UI component library
(not Radix) — components use the `render` prop for polymorphism, not `asChild`, and a `Button` wrapping a
non-native element (e.g. a `next/link`) needs `nativeButton={false}` or Base UI logs an a11y warning.

The browser never holds the API key: the shorten form posts same-origin to
`frontend/src/app/api/shorten/route.ts`, which attaches `X-API-Key` server-side. That route is
reachable only through the gateway (`api-gateway/nginx.conf` proxies `/api/`, `/_next/`, `/`
and `/dashboard` to the `frontend` container), which is what rate-limits it per client address —
a Next.js route handler is given no connection address, so it cannot do that itself.
`api-gateway/rate-limit-test.sh` drives the real config in docker to prove both the limit and
that `/shorten` and the redirect regex still reach their own services.

## TLS

The gateway terminates TLS and serves nothing over plaintext: port 80 only 301s to HTTPS.
Certificates and keys are never committed -- `api-gateway/generate-dev-cert.sh` (Compose,
mounted from the gitignored `api-gateway/certs/`) and `k8s/deploy.sh` (cluster, `--set-file`
into the chart's `hopr-tls` Secret) each mint a self-signed pair on first run, so every local
client needs `curl -k` or a clicked-through browser warning. `README.md`'s "TLS" section is
the authoritative write-up, including what a real cert-manager + Let's Encrypt setup changes.
`api-gateway/rate-limit-test.sh` asserts the redirect and HSTS alongside the rate limit.

## Database schema

ScyllaDB schema lives in `db-migration/` as Flyway CQL migrations — see `db-migration/README.md`
for how to run them and for the Scylla/Flyway constraints (counters vs. tablets, no semicolons in
CQL comments) that will bite anyone editing a migration. Never apply DDL by hand via `cqlsh`.
The services read and write it through Spring Data Cassandra (`common`'s `UrlMapping`/`UrlRepository`);
MongoDB is gone. `UrlMapping`'s CQL column names are spelled out because the table is snake_case.
Every production write of `urls` claims its short key through the `IF NOT EXISTS` lightweight
transaction in the shortener's `DbCacheSaver.saveUrlMappingIfAbsent` — a non-applied write becomes
a 409 for a user-chosen alias and a bounded retry under a freshly generated key otherwise.
`UrlRepository.save` is a plain CQL INSERT, i.e. an upsert that silently overwrites an existing
row, and is not used to write. A shorten request may set `expiresInSeconds`; `DbCacheSaver` then
writes the row `USING TTL` so ScyllaDB itself expires and removes it — no app-level cleanup job.
Because a TTL'd row can vanish out from under a fixed-TTL cache entry, both the write-through
cache in `DbCacheSaver` and the read-through cache in resolver's `ResolverUseCase` skip caching
whenever `UrlMapping.getExpiresAt()` is set, so an expired link can't keep resolving from a stale
cache entry after ScyllaDB has already dropped the row.

`DbCacheSaver` no longer primes the cache itself: after a successful LWT insert it writes a
durable outbox row (`OutboxEventWriter`, `db-migration`'s V5 `outbox_events` table), and
`CachePrimePoller` (`@Scheduled`, `shortener-service`'s `urlshort/infra/outbox` package) is the
only thing that reads pending events and primes Redis from them, so a crash between persist and
cache-priming is a recoverable outbox row instead of a silently lost cache entry. Because Scylla
refuses a batch that mixes a conditional (LWT) statement with another table, the outbox write is
a second, unconditional insert issued right after the LWT applies — not the same atomic
operation — see `OutboxEventWriter`'s javadoc for the actual guarantee. `outbox_events` partitions
by `(day bucket, status)` so the poller's only query is a single-partition read of today's (and
yesterday's) PENDING events; marking an event processed is a delete+insert into a different
partition, which is fine for a non-conditional batch. This is also the intended plug-in point for
a future Kafka-publishing consumer over the same table.

## Resilience

Spring Boot 4 ships no AOP starter and nothing puts AspectJ on the classpath, so Resilience4j's
`@CircuitBreaker`/`@Retry` annotations are inert here — the aspect beans never register. Apply
Resilience4j programmatically through the autoconfigured `CircuitBreakerRegistry` instead (see
`shortener-service`'s `KeyGenClient`). Thresholds live in `config-server/src/main/resources/config-repo/`.

## Observability

Tracing and Prometheus metrics are on in all three Spring services. Two Boot 4 traps: the
`micrometer-tracing-bridge-*` artifact alone leaves you with Boot's `NoopTracer` — the
auto-configuration that builds a real `Tracer` lives in `spring-boot-micrometer-tracing-brave`,
which must be declared too (it does not pull the bridge transitively, so both lines are load-bearing); and `shortener-service` defines its own `WebClient.Builder`
(`WebClientConfig`) instead of Boot's, so it has to be handed the `ObservationRegistry`
explicitly or the shortener->keygen hop starts a fresh trace. Both are covered by
`TracePropagationIntegrationTest` (shortener) and `TraceContinuationIntegrationTest` (resolver);
sampling and endpoint exposure live in `config-server/src/main/resources/config-repo/`.

## Link management

`shortener-service`'s `GET/GET {shortKey}/PATCH {shortKey}/DELETE {shortKey}` under `/links`
(`LinkManagementController`/`LinkManagementUseCase`) let a caller list, read, update, or delete
any previously-shortened link, gated by the same `X-API-Key` filter as `/shorten` — see
`ApiKeySecurityConfig`. There is no per-owner scoping: the `urls` table's `owner_id` column
exists but nothing populates or enforces it yet (deferred to a future multi-tenancy phase), so
any caller holding the key can manage any link. `GET /links` is an unscoped full-table scan
over Scylla's native paging state (`CassandraPageRequest`, base64-encoded as `pageToken`) —
honest "every link in the system", not "my links"; a real per-owner listing needs a
query-first secondary table keyed by `owner_id` before it can narrow. Update/delete evict only
`shortener-service`'s own Redis cache entry; the resolver runs an independently-namespaced Redis
cache (see Observability/Resilience sections' Boot 4 traps — same pattern applies to cache
naming) and can keep serving a stale mapping for up to its 12h TTL after an update/delete.

## API keys

`POST /shorten` requires an `X-API-Key` header; the resolver's redirect path is deliberately
public and must stay that way. Validation lives in `shortener-service`'s `security` package: the
filter is registered against the `/shorten` URL pattern only (a bare `@Component` filter would map
to `/*` and break actuator probes). Only SHA-256 digests of accepted keys are configured
(`shortener.api-key.hashes` / `SHORTENER_API_KEY_HASHES`), which is why they live in non-secret
config (`.env`, the chart's ConfigMap) rather than `.env.secrets` — a digest cannot be replayed.
Local development key: `hopr-local-dev-key`.

## Local config and secrets

`docker-compose.yml` reads `.env` (non-secret config) and `.env.secrets` (credentials), plus
`.env.frontend.secrets` for the frontend container alone -- it gets only its own
`SHORTEN_API_KEY`, mirroring the chart's single `secretKeyRef`. All three are gitignored and
created from the committed `.example` files. The split
mirrors the Helm chart's `hopr-config` ConfigMap vs `hopr-secret` Secret — add a new variable to
whichever pair it belongs to, in both Compose and the chart. `DOCKER_COMPOSE_GUIDE.md` step 2
is the setup instruction; a fresh clone without those copies fails `docker compose config`.

All five service Dockerfiles (the four Spring services plus `frontend/`) run as the
unprivileged `appuser` (uid 1001); the guide's step 6
has the command that verifies it.

## Tests

`scripts/run-tests.sh` runs every suite (Gradle, then `frontend/` `npm test`) and is the
deterministic test command in `.no-mistakes.yaml`; no-mistakes reads that file from `main`
only, so edits to it take effect after they land there.

## Inter-service contract tests

`docs/contracts/` holds one JSON fixture per cross-service HTTP shape (currently keygen-service's
`GET /generate`), read by a test on both sides of the boundary — see `docs/contracts/README.md`
for why a shared fixture plus two JUnit tests was chosen over a contract-testing framework, and
`KeyGenControllerContractTest` (keygen-service, provider) / `KeyGenClientContractTest`
(shortener-service, consumer) for the pattern to copy for a future service boundary (e.g. Phase
5's event-driven work). These run as part of `./gradlew test` like any other test.

## Events (Kafka)

A single-broker, KRaft-mode Kafka (`docker-compose.yml`'s `kafka` service /
`k8s/hopr-chart/templates/kafka.yaml`) carries two producer-side events, both defined as
shared records in `common/src/main/java/com/pcaokhai/common/event/` and schema-documented in
`docs/events/` (same fixture-file pattern as `docs/contracts/`): `UrlCreatedEvent`, published
from `shortener-service`'s `CachePrimePoller`/`UrlCreatedEventPublisher` for every outbox
record it processes (piggybacking on the outbox mechanism from the prior PR rather than a
second poll-and-mark-processed loop over the same table), and `ClickEvent`, published
fire-and-forget from `resolver-service`'s `ResolverUseCase`/`ClickEventPublisher` after a
successful redirect, with no outbox record behind it since a redirect writes nothing durable
to hang one on. Both services' `KafkaTemplate` beans are hand-built in their own `KafkaConfig`
(not Boot's autoconfigured one) because Boot's `KafkaTemplate<Object, Object>` doesn't satisfy
a `KafkaTemplate<String, <EventType>>` injection point — generic bean matching is invariant.
`click-analytics-service` is the only consumer: it reads `url-clicked` (not `url-created` —
nothing in the two tables below has a natural home for "a link was created") under the
stable, documented consumer group `click-analytics-service` (`ClickEventConsumer`,
`ClickEventConsumer.GROUP_ID`), so a future second replica added for scaling splits the
topic's partitions instead of each instance reprocessing every record. It writes each
`ClickEvent` into Phase 1's previously-unused `url_click_counts` (a Scylla counter table,
`UPDATE ... SET clicks = clicks + 1`) and `url_click_events` (`ClickAnalyticsRecorder`). Kafka
is at-least-once, so a redelivered click can double-count in `url_click_counts` — there is no
idempotency key to dedupe a counter increment on; `url_click_events`' primary key
`(short_key, day, clicked_at)` does not have this problem, since a redelivery of the same
event carries the same `clickedAt` and simply overwrites the identical row instead of
duplicating it. A malformed message is logged and skipped (`ErrorHandlingDeserializer` +
`DefaultErrorHandler` with no retry) rather than blocking the partition or crashing the
listener. It is deliberately its own Spring Boot service/module rather than a component
bolted onto `resolver-service`, so an analytics-side slowdown or crash can never affect the
hot redirect path, and it can be deployed and scaled independently of it.

## Load testing

`scripts/load-test-resolver.sh` runs a k6 load test (`resolver-service/loadtest/`) against the
resolver's redirect path on a real docker-compose stack, and is not part of `scripts/run-tests.sh`
— see the script's header comment for why and its intended cadence. README's resolver latency
claim was corrected from an unqualified "sub-millisecond" to the actual measured p50/p95/p99
after running it; re-run and update that claim if resolver caching, Redis/Scylla topology, or the
hot path changes.
