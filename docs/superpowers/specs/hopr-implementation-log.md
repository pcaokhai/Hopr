# Hopr — Implementation Log: Features, Bugs, Decisions & Trade-offs

> **Superseded by `docs/phases/` — a more complete phase-by-phase write-up covering all 6 phases plus testing instructions; this file only covers Phases 1-3.**
>
> Companion to `docs/hopr-production-review.md` (the original review) and
> `docs/superpowers/specs/2026-09-10-hopr-production-roadmap-design.md` (the roadmap this
> log tracks). This document records, per PR, what was actually built, every bug the
> automated review caught before merge, and every decision made along with its trade-off —
> so a later reader can see not just what the code does today, but why it looks the way it
> does and what alternatives were rejected.

## Phase 1 — Correct data foundation

### PR 1 — Worker-ID fix (`fix(keygen-service): coordinate worker-ID allocation via Redis lease`, #8)

**What was built:** replaced `DefaultNodeInfoProvider`'s IP/hostname-derived Snowflake
worker ID (prone to collision when `keygen-service` scales horizontally, since container IPs
are often sequential) with a Redis-lease-based allocator (`WorkerIdLeaseAllocator` +
`RedisNodeInfoProvider`) that atomically claims a worker ID on startup with a TTL, so a
crashed instance's ID is eventually reclaimed.

**Decision — Redis lease vs. StatefulSet ordinal:** the brief allowed either approach.
Redis lease was chosen because Redis Cluster is already a dependency in this stack, avoiding
new infrastructure, and it works regardless of whether `keygen-service` is deployed as a
plain Deployment or a StatefulSet. The trade-off: it adds a runtime dependency on Redis being
available at keygen-service startup, versus a StatefulSet ordinal's zero-coordination
determinism (which only works under StatefulSet deployment).

**Bugs:** none found in review — this PR shipped clean on the first pass.

---

### PR 2 — ScyllaDB schema + infra (`#9`)

**What was built:** a 3-node ScyllaDB cluster (docker-compose + k8s StatefulSet), matching
the HA pattern already used for Redis Cluster, with the keyspace and three tables
(`urls`, `url_click_counts` as a counter table, `url_click_events` partitioned by
`(short_key, day)`) defined as **Flyway** versioned CQL migrations (`V1`–`V4`) rather than
hand-run `cqlsh` scripts — introducing schema-as-code discipline per the captain's explicit
request mid-brainstorm.

**Bug 1 — Scylla running in developer mode on the "production" k8s chart:** the initial
implementation left `--developer-mode 1` and `--overprovisioned 1` on the StatefulSet (values
appropriate only for the laptop-dev docker-compose block), which silently disables Scylla's
production durability/I-O checks — an unflagged data-durability downgrade on infrastructure
built at RF=3 specifically for durability. **Decided:** remove the flags from the k8s chart
only, leave docker-compose's dev-mode flags alone (they're explicitly documented as
laptop-only there).

**Bug 2 — dead config key:** `values.yaml` introduced `scylladb.keyspace` but nothing read
it (the real keyspace name lives in the Flyway migration). **Decided:** remove it.

**Bug 3 (introduced by fixing Bug 1) — production-mode boot failure risk:** dropping
developer mode meant Scylla's environment checks became strict, but the chart's PVC had no
pinned StorageClass and no baked `io_properties`. On a cluster without XFS-formatted storage
available, the first Scylla pod could fail its filesystem check outright, or take long enough
running `iotune` on first boot that the readiness probe (max ~360s) kills it before it's
ready — and since pods start in `OrderedReady` sequence, the other two nodes never start
either, so the RF=3 ring never forms. **Escalated to captain** (a genuine deployability
trade-off, not a mechanical fix): pin an XFS-backed StorageClass in the chart (correct but
locks the chart to clusters offering that storage class) vs. document the requirement instead
(portable, but the requirement isn't enforced). **Captain decided:** document the requirement
in the chart's README/values comments rather than hard-pin a StorageClass, keeping the chart
deployable on generic/local clusters for a learning project.

---

### PR 3 — Repository swap to Cassandra (`refactor: cut shortener and resolver services over to ScyllaDB`, #10)

**What was built:** cut `shortener-service` and `resolver-service` over from
`spring-boot-starter-data-mongodb` to `spring-boot-starter-data-cassandra` against the
cluster PR 2 stood up — `UrlMapping` re-annotated for Cassandra, `UrlRepository` now extends
`CassandraRepository`, MongoDB removed entirely, Testcontainers swapped to a real Scylla
node. Deliberately still used a plain `INSERT` (the uniqueness fix was scoped to the next PR)
so the infra cutover and the correctness fix could be reviewed independently.

**Incident — crewmate wedged, not a real blocker:** the implementing session's machine went
to sleep mid-response overnight ("Your computer went to sleep mid-response"), leaving 24
files of real, uncommitted progress on the branch. **Recovered** by interrupting and
redirecting the same worker to resume in place (not relaunching from scratch) — no work
lost. Later in the same task, the worker also hit its own account-level session rate limit
mid-pipeline; this was treated as a known, self-clearing external wait rather than a wedge,
and the worker resumed on its own once the limit reset.

**Bug 1 — schema-before-app-start race:** both `docker-compose.yml`'s `depends_on` (short
form, no `condition: service_healthy`) and the k8s deploy script started the app services
before the Flyway migration had necessarily run, so a fresh checkout could crash both JVMs
with `AllNodesFailedException`/`InvalidKeyspaceException` before the schema existed. A full
Helm pre-install migration Job would be the durable fix but was out of scope for this PR.
**Decided:** the smallest remedy — `condition: service_healthy` on the Scylla dependency in
compose, plus reordering the k8s deploy script's migration step ahead of app startup, backed
by a new `urlServices.enabled` Helm flag to gate app-service rollout until the migration
completes.

**Bug 2 — duplicated test schema:** both services' `BaseIntegrationTest` carried a
hand-mirrored copy of the keyspace/table DDL (`scylla-init.cql`) instead of pointing at the
real Flyway migration files, meaning a future schema change (e.g. a new column) wouldn't be
exercised by the very test meant to catch column-mapping drift. **Decided:** point
Testcontainers `withInitScripts` at the real migration files instead.

**Bug 3 — dead test dependency (×2):** `testcontainers-junit-jupiter` was added to both
service build files but neither uses `@Testcontainers`/`@Container` (the codebase
deliberately uses a singleton container pattern instead). **Decided:** remove from both.

**Bug 4 — deploy-script idempotency regression:** the fix for Bug 1 made `k8s/deploy.sh`
unconditionally disable-then-re-enable the URL services on every run, so re-running the
script against an already-converged cluster (which it's documented to support) now forced a
full teardown/restart of both app services — turning a no-op redeploy into ~a minute of
`502`s. **Escalated to captain** (a real UX/behavior trade-off: accept the blip on every
re-run vs. add a `helm status` check to skip the disable pass when already deployed).
**Captain decided:** skip the disable pass on repeat runs, preserving idempotency.

**Bug 5 — the flag-based sequencing itself had a residual defect:** a fix-review pass
surfaced that the `urlServices.enabled` flag machinery (introduced entirely by the fixes
above, not part of the PR's original stated scope) carried its own boot-ordering defect
("`deploy-kill-under-set-e`"). The reviewer's own recommendation was to back the flag out
entirely in favor of a narrower fix (migrate before a single `helm upgrade`, accept the app
pods' normal restart) — flagged as a case of incremental fixes accreting machinery around a
possibly-wrong abstraction, and escalated on those grounds rather than decided directly.
**Captain decided:** keep the flag-based approach and harden it rather than back it out.

---

### PR 4 — LWT uniqueness fix (`fix(shortener-service): close alias creation race with INSERT ... IF NOT EXISTS`, #11)

**What was built:** replaced the check-then-save pattern (`AliasAvailabilityValidator`'s
`findById` followed by a separate save) with a single `INSERT ... IF NOT EXISTS` lightweight
transaction (Paxos-backed) for the custom-alias path, run at `SERIAL` consistency, mapping a
failed conditional apply to `409 Conflict`. A test reproducing the original race (two
concurrent requests for the same alias) proved the old bug (`[200,200]`, one silently
overwriting the other) and its closure (`[200,409]`).

**Bug — the PR's own scoping was wrong:** the brief explicitly told the implementer the
auto-generated-key path didn't need the LWT, reasoning that PR 1's worker-ID fix already
guarantees Snowflake ID uniqueness *among generated keys*. Review caught that this missed the
actual failure mode: generated keys (7-char base62) and custom aliases (3–20 char
alphanumeric) share the same key-space, so a generated key can still collide with a
previously *claimed alias* — and the generated-key path's plain upsert would silently
overwrite that claimed row, reproducing the exact bug this PR exists to close, just via the
other write path. **Decided** (this was a defect in Firstmate's own brief, not a scope
question): route the generated-key path through the same atomic write, and on a collision,
retry with a newly generated key (bounded to a few attempts) rather than surfacing an error —
since the collision is purely internal and invisible to the requesting user.

**Cleanup considered and skipped:** the fix left `KeyGenResolver` as a bare passthrough with
no logic of its own (its only branch was deleted). Flagged as removable with zero behavior
change, but explicitly optional ("not required for the PR's intent either way"). **Decided:**
leave it — out of scope, keeps the diff focused.

---

## Phase 2 — Observability & resilience

### PR 1 — Circuit breaker + timeout on `KeyGenClient` (`#12`)

**What was built:** `KeyGenClient`'s reactive `WebClient` call, which previously `.block()`ed
with no timeout inside Spring MVC's blocking thread pool (risking cascading failure — enough
hung requests exhaust Tomcat's thread pool with no exception ever thrown), now has an 800ms
bound plus a Resilience4j circuit breaker, exposed via Actuator. The previously broad
`catch (Exception e)` was narrowed to distinguish "keygen unavailable" (degrade gracefully)
from a genuine unexpected exception (log distinctly rather than silently swallow).

**Platform note, not a bug:** Spring Boot 4 dropped the AOP-based Resilience4j starter used
in older tutorials/docs, so the circuit breaker had to be wired programmatically rather than
via annotations.

**Bugs:** none found in review.

---

### PR 2 — Distributed tracing + Prometheus (`#13`)

**What was built:** Micrometer Tracing (Brave) + `micrometer-registry-prometheus` wired into
`shortener-service`, `resolver-service`, and `keygen-service` (config-server later joined via
PR 3's graceful-shutdown work), giving a shared trace ID across the multi-hop request path;
`api-gateway` is nginx, not a Spring service, so no Micrometer configuration applies there.

**Decision — accepted, deliberately deferred security gap:** exposing `/actuator/prometheus`
on the same port nginx already proxies publicly makes the full metric set (JVM internals,
per-URI timing, circuit-breaker state) curlable by anyone — extending a pre-existing
exposure (health details were already public) rather than creating the first one, but a real,
deliberate widening. **Escalated to captain** (ship as-is vs. lock down now via a separate
management port or an nginx deny rule). **Captain decided:** ship as-is; Actuator lockdown is
deferred to Phase 3 (security hardening) rather than scope-creeping the observability PR.

**Near-miss — a wrong decision caught and corrected by the pipeline itself:** Firstmate's
initial fix-round instruction was to remove an apparently-duplicate
`io.micrometer:micrometer-tracing-bridge-brave` dependency declaration, reasoning the Spring
Boot starter already brought it transitively. The fix-reviewer independently verified this
against the actual Gradle module metadata and found the reasoning was **wrong** — the bridge
is *not* transitive, and removing it would have silently left tracing running on Boot's
no-op tracer with no build error to signal the regression. **Decided:** accept the deviation;
keep the explicit dependency, with an `AGENTS.md` note recording why for future readers.

---

### PR 3 — Graceful shutdown (`#14`)

**What was built:** `server.shutdown=graceful` plus a 20-second drain window across the three
JVM services (config-server later added, see below), aligned with k8s Deployments configured
`maxUnavailable: 0`, a 30s `terminationGracePeriodSeconds`, and a 5s `preStop` sleep hook; the
same drain timeout applied to `stop_grace_period` in docker-compose. `api-gateway` (nginx)
needed no equivalent change. A new integration test proves an in-flight request completes
rather than being dropped when the app context is closed mid-request.

**Bug 1 — the regression test didn't actually guard the shipped configuration:** the test
booted a throwaway app and passed `server.shutdown=graceful` inline as test-only properties,
so it verified Spring Boot's own lifecycle mechanism in the abstract, not the specific values
this PR shipped in `config-repo/application.yml` — deleting the shipped config entirely would
have left the test green. **Decided:** point the test at the real shipped config-repo values
instead of independent literals, so a future accidental deletion is actually caught.

**Bug 2 — `config-server` itself was missed from the PR's own stated scope:** the brief said
"across the backend services," but `config-server` — a Spring backend service present in
both deployment targets — is not a config *client* of itself, so it inherited neither the
graceful-shutdown property nor a matching grace period. A redeploy of `config-server` could
therefore cut an in-flight config fetch from a *starting* app pod mid-response, the exact
failure class this PR exists to prevent. **Decided:** this was a completion of the already-
stated intent, not new scope — added the same properties and grace periods to `config-server`.

**Bug 3 — the `config-server` fix was applied inconsistently with the other three
services:** a follow-up review pass caught that `config-server`'s k8s manifest got the
termination grace period but not the matching `preStop: sleep 5` hook the other three
services already carry (closing the same connection-refused race the hook was originally
added for), and that the regression test's new config-repo assertion covered the app
services' copy of the properties but not `config-server`'s own separate copy. **Decided:**
apply the identical, already-accepted pattern to close both gaps — pure consistency fixes,
not new scope.

---

## Phase 3 — Security hardening (in progress)

### PR 1 — Input validation on `/shorten` (`#15`)

**What was built:** `ShortenRequest.longUrl` gained `@NotBlank`, a scheme-restricted pattern
(`http`/`https` only, closing an open-redirect vector since any string was previously
accepted and later 307-redirected to), and a 2048-character `@Size` limit, enforced via
`@Valid` and surfaced as `400 Bad Request` through a new `InvalidRequestMessage` DTO.

**Decision — kept a component initially flagged as unnecessary:** review noted that
`@Valid` alone (Spring's default validation-error response) would satisfy the letter of "add
input validation," making the custom `InvalidRequestMessage` DTO and exception handler
technically more than the intent strictly required. **Decided:** keep it — it matches the
existing `AliasInvalidFormatMessage` convention already used elsewhere in the codebase,
giving API consumers one consistent error shape rather than mixing Spring's raw default
format with the app's own.

**False alarm, caught on its own re-verification:** an initial review pass flagged that the
regex's `$` anchor could theoretically let a trailing newline through (Java's `$` matches
before a final line terminator), risking a downstream `IllegalArgumentException` on resolve.
The fix-review step re-verified this against Hibernate Validator's actual behavior
(`PatternValidator` calls `Matcher.matches()`, which requires a full-string match) and found
the "bug" never existed — the pattern was correct before and after. **No behavior change
needed**, though the clearer `\z` anchor was kept.

**Bug 1 — case-sensitive scheme rejected valid URLs:** the scheme match was case-sensitive,
so `HTTPS://example.com` — a valid URL per RFC 3986 (schemes are case-insensitive) and
routinely produced by copy-paste from mail clients/documents — was rejected with `400`,
narrower than the stated intent of "accept http/https." **Decided:** made the scheme match
case-insensitive.

**Bug 2 — the regex still let URI-illegal characters through:** the pattern only excluded
whitespace after the authority, so characters illegal in `java.net.URI` (`<`, `"`, `` ` ``,
`{`, `}`, `|`, `\`, `^`) still passed, got persisted, and then permanently crashed
`ResolverController`'s `URI.create(longUrl)` with a `500` on every future resolve of that
key — precisely the class of bug this PR exists to prevent. **Decided:** rather than
continuing to patch the regex character class by character class, add an actual
`URI.create()` parseability check as a backstop after the existing checks, guaranteeing
nothing is ever persisted that the resolver can't later parse — closing the whole bug class
at once instead of the one instance flagged.

---

### PR 2 — Non-root containers + secret management (`#16`)

**What was built:** a `USER appuser` directive added to all four service Dockerfiles
(previously all ran as root by default); Compose credentials (Scylla/Redis, following
MongoDB's earlier removal) moved out of plaintext into a `.env`/`.env.secrets` split
mirroring the k8s chart's existing `ConfigMap`/`Secret` pattern from Phase 1, with committed
`.env.example`-style templates; the k8s chart's existing `secret.yaml` was verified actually
wired into the relevant Deployments rather than assumed complete. The full compose stack was
verified end-to-end (~27 minutes of real integration testing against live Scylla/Redis
containers) to still start healthy with every container running as the non-root user.

**Incident — transient environment failure, not a real blocker:** the implementing session
hit a one-off `Can't reach the API server (ENOTFOUND)` error right after initial branch
setup, with no work yet at risk. **Recovered** by interrupting and redirecting the same
worker to resume; it picked back up cleanly.

**Bugs:** none found in review — this PR shipped clean on the first pass, though its
validation step ran long due to the genuine full-stack integration verification it required.

---

## Cross-cutting decisions

- **Merge authority (`yolo`):** started `off` (every PR required the captain's explicit
  merge). After the captain asked why so many turns were spent waiting on review, the captain
  turned `yolo: on` for Hopr specifically — Firstmate now merges any PR that is both green on
  CI and within the accepted scope, without waiting for a manual "merge" instruction, while
  still surfacing the concept write-up for the captain to read afterward.
- **PR granularity:** the roadmap was deliberately split into one concept per PR rather than
  one PR per phase, so each merged diff maps to a single learnable pattern (e.g. the Scylla
  migration alone became three separate PRs: schema/infra, repository swap, and the
  uniqueness fix — rather than one large migration PR).
- **Schema-as-code:** Flyway (with the Cassandra/Scylla community plugin) was adopted for
  every schema change from Phase 1 onward, per the captain's explicit request, rather than
  hand-run DDL scripts.
