# Hopr — Production Readiness Gap Report

## What I did

The captain asked for gstack `/review` + `/autoplan` against the current repo state.
Both are diff/PR-based pipelines: `/review` step 1 explicitly stops with "nothing to
review" when there's no diff against the base branch, and `/autoplan` chains the same
PR-oriented review skills. This worktree is a clean, unmodified checkout at detached
HEAD == `origin/main` (verified: `git diff origin/main --stat` empty, `git status`
clean). There is no PR, no branch, no diff for either pipeline to operate on.

Rather than force a non-applicable diff tool, I ran the equivalent manual audit using
the same categories those skills check (security, error handling, secrets/config,
observability, CI/CD, deployment) by reading the actual code, k8s chart, CI workflows,
and frontend across all 5 backend services + the frontend. Findings below are ordered
by severity/risk, each with file:line evidence.

## Architecture as found

- `api-gateway` is **not a Java service** — despite the brief's assumption, it's a
  single `nginx.conf` (`api-gateway/nginx.conf`) reverse-proxying to
  `shortener-service` and `resolver-service`. No Dockerfile for it exists at all
  (config-server, keygen-service, resolver-service, shortener-service each have one;
  api-gateway does not — unclear how/if it's containerized for the k8s chart, which
  also has no api-gateway/nginx template).
- Backend: keygen-service (Snowflake ID + Base62), shortener-service, resolver-service,
  config-server (Spring Cloud Config, native profile, serving `config-repo/*.yml`).
- Frontend: Next.js app, only the landing-page shorten form is wired to the real
  `POST /shorten`; dashboard/analytics are 100% mock data (`frontend/README.md`).

## Prioritized gaps

### P0 — Critical (block production)

1. **No authentication or authorization anywhere in the system.**
   No `spring-boot-starter-security` dependency in any service's `build.gradle`
   (grepped repo-wide, zero hits). `/shorten`, all resolve endpoints, and the
   config-server itself are open to the internet with no API key, no rate-limit-per-
   user, no auth. Anyone can mint unlimited short links or query the config server.

2. **No input validation on the shorten request body.**
   `common/src/main/java/com/pcaokhai/common/url/model/dto/ShortenRequest.java:27-30`
   — `longUrl` and `alias` are plain `String` fields with zero Bean Validation
   annotations, and `ShortenerController.shortenUrl`
   (`shortener-service/.../infra/router/ShortenerController.java:44`) takes
   `@RequestBody ShortenRequest request` with no `@Valid`. Only `alias` gets format-
   checked (`AliasFormatValidator.java`); `longUrl` is never validated for scheme,
   length, or well-formedness before being persisted and later handed back verbatim
   as a `307` redirect target in `ResolverController.resolve`
   (`resolver-service/.../infra/router/ResolverController.java:44-47`,
   `URI.create(longUrl)` with no scheme allowlist). This is a live open-redirect /
   phishing vector (`javascript:`, `data:`, arbitrary hosts) and a `NullPointerException`
   waiting to happen for a missing `longUrl`.

3. **No global/catch-all exception handler on any service.**
   `UrlShortenerExceptionHandler.java` (and the resolver/keygen equivalents) only map
   three specific domain exceptions to responses; there's no fallback
   `@ExceptionHandler(Exception.class)`. An unmapped exception (e.g. the NPE from #2)
   falls through to Spring Boot's default error handling, which can leak stack traces
   / internal class names depending on `server.error.include-*` settings — none of
   which are configured anywhere (checked all `application*.yml` under
   `config-server/src/main/resources/config-repo/`).

4. **Default, weak credentials baked into the Helm chart.**
   `k8s/hopr-chart/values.yaml:8-11` — `mongodb.rootUsername: root`,
   `mongodb.rootPassword: password` (plaintext, in version control), `redis.password: ""`
   (empty). `k8s/hopr-chart/templates/secret.yaml:8-9` renders these straight into a
   `Secret` with no override enforced — a `helm install` with defaults deploys with
   root/password and an unauthenticated Redis cluster. Given the project is registered
   `no-mistakes-prod-only`, this is the single highest-risk item: it will work exactly
   as configured, in production, with attacker-guessable creds.

5. **MongoDB has no persistent storage and no replication.**
   `k8s/hopr-chart/templates/mongodb.yaml` — a `Deployment` (not `StatefulSet`),
   `replicas: 1`, no `volumeMounts`/`PersistentVolumeClaim` anywhere in the template.
   Every pod restart (deploy, crash, node drain) wipes all shortened-URL data.

### P1 — High (should fix before real launch)

6. **CORS wide open (`*`) with credentials-adjacent headers allowed.**
   `api-gateway/nginx.conf:30-33,39-41` and `k8s/hopr-chart/templates/ingress.yaml:9-11`
   both set `Access-Control-Allow-Origin: '*'` while also allowing
   `Authorization` in `Access-Control-Allow-Headers` — a combination that's
   inconsistent (browsers block credentialed requests with wildcard origin, but it
   signals no real CORS policy exists) and won't work once any auth is added.

7. **No TLS anywhere.** `nginx.conf` only `listen 80`; `ingress.yaml` has no `tls:`
   block. `shortener.domain` in `config-repo/shortener-service.yml` and
   `values.yaml:15` default to `http://`. All shortened links and the config-server
   traffic are plaintext.

8. **Actuator `health` exposed with full details and no protection.**
   `config-repo/{keygen,resolver,shortener}-service.yml` all set
   `management.endpoint.health.show-details: always` with no Spring Security to gate
   `/actuator/*`, publicly leaking Mongo/Redis connectivity state and internal
   component health to anyone who can reach the pod.

9. **Config server has no authentication.** Anyone who can reach
   `config-server:8888` can pull `/shortener-service/default` etc. and see the full
   resolved config (env var names, structure); combined with #4's default secrets in
   the K8s `Secret`, an attacker with cluster network access gets everything needed to
   read/write the datastore directly.

10. **No CI coverage for the frontend or for building/pushing Docker images.**
    `.github/workflows/gradle-build-main.yml` and `-pull-requests.yml` only run
    `./gradlew build` / `./gradlew clean build` — no `npm test`/`npm run build`/`eslint`
    step for `frontend/`, no Docker image build, no image push, no deploy step, no
    security/dependency scanning beyond Dependabot version bumps. There is no path from
    a merged PR to a running deployment other than someone manually running
    `k8s/build-and-load.sh` / `deploy.sh` (which target `kind`, i.e. local only —
    `image.pullPolicy: Never` in `values.yaml:5` confirms these charts have never been
    pointed at a real registry/cluster).

11. **No resource requests/limits, no HPA, `replicas: 1` on every Deployment.**
    Checked `shortener-service.yaml`, `resolver-service.yaml`, `keygen-service.yaml`,
    `config-server.yaml` templates — none set `resources:`, none set replica count above
    1, no `PodDisruptionBudget`. A single pod eviction takes the whole service down;
    a noisy-neighbor pod can starve these of CPU/memory with no ceiling.

12. **No structured logging / tracing / metrics pipeline.** Only `health,info` are
    exposed via actuator (see #8) — no `prometheus` endpoint, no
    micrometer/OpenTelemetry dependency in any `build.gradle`, no log aggregation
    config. There is no way to see request volume, error rates, or latency once this
    is running anywhere but a developer's terminal.

13. **Frontend has no real auth and ships mock data as if real.** Per
    `frontend/README.md`'s own "What's real vs. mocked" section, `/dashboard` and
    `/dashboard/[slug]` are open (no login) and backed entirely by fabricated data in
    `src/lib/mock-data.ts` / `mockAnalyticsFor`. This is fine for a demo but is not a
    gap the backend alone can close — it needs list/analytics/auth endpoints before
    the frontend can be considered production-ready end to end.

### P2 — Medium (quality/hardening)

14. **Test coverage is uneven and `api-gateway` has zero tests** (it's not code, but
    its nginx rules — CORS, the regex route split documented by the `ponytail:` comment
    in `k8s/hopr-chart/templates/ingress.yaml` — have no automated verification at all).
    `config-server` has only 2 test files. No integration tests exercise the full
    shorten→resolve flow across services (each service's tests are isolated unit/slice
    tests per the `src/test` counts: keygen 18, resolver 8, shortener 24, config-server 2).

15. **No rate limiting on the resolver path.** `nginx.conf:46-48` — the regex location
    for resolving short keys has no `limit_req` applied (only `/shorten` does at
    `nginx.conf:26`), so the redirect endpoint (the one that will get the most public
    traffic) is unprotected from abuse/scraping.

16. **No documented secrets-rotation or environment-parity story.** `k8s/README.md`
    and `k8s/deploy.sh` only describe local `kind` usage; there's no staging/prod
    values file, no External Secrets/Vault integration, nothing to change the
    real-deployment defaults from #4 short of manually editing `values.yaml`.

## Recommendation order

Fix in this order: **#4 (rotate/remove default creds, wire real secret management) →
#1 (add auth to the gateway/services) → #2+#3 (validate `longUrl`, add scheme
allowlist, add catch-all exception handler) → #5 (StatefulSet + PVC for Mongo) →
#7/#6 (TLS + real CORS policy) → #8/#9 (lock down actuator + config-server) → #10
(CI: frontend build/test, Docker build+push, a real deploy path) → #11/#12 (resource
limits, replicas, observability)**. Items #13-16 are real but can trail a first
production cut once the above are closed.

No code was changed and no PR was opened — this is a scout report only.
