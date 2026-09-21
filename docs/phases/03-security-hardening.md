# Phase 3 — Security Hardening

**Goal:** close the open-redirect vector, stop running as root, stop leaking credentials, and
encrypt traffic.

## PR 1 — Input validation on `/shorten` (#15)

### What was built
`ShortenRequest.longUrl` gained `@NotBlank`, a scheme-restricted pattern (`http`/`https` only,
closing an open-redirect vector — any string was previously accepted and later 307-redirected
to), and a 2048-character `@Size` limit, enforced via `@Valid`, surfaced as `400 Bad Request`
through a new `InvalidRequestMessage` DTO.

### Decisions & trade-offs
- **Kept a component initially flagged as unnecessary:** `@Valid` alone would satisfy the letter
  of "add validation," but the custom error DTO matches the existing `AliasInvalidFormatMessage`
  convention already used elsewhere — kept for a consistent error shape across the API.
- **False alarm, self-corrected:** a review pass flagged the regex's `$` anchor might let a
  trailing newline through; re-verification against Hibernate Validator's actual behavior
  (`Matcher.matches()` requires a full-string match) showed the "bug" never existed. No change
  needed, though the clearer `\z` anchor was kept.
- **Bug — case-sensitive scheme:** rejected valid, commonly-pasted URLs like
  `HTTPS://example.com` (RFC 3986 schemes are case-insensitive). **Fixed:** case-insensitive
  scheme match.
- **Bug — regex still allowed URI-illegal characters:** characters like `<`, `"`, backticks
  passed the regex, got persisted, then permanently crashed `ResolverController`'s
  `URI.create(longUrl)` with a `500` on every future resolve. **Fixed:** added an actual
  `URI.create()` parseability check as a backstop, closing the whole bug class rather than
  patching one character at a time.

### How to test
```bash
./gradlew :shortener-service:test --tests "*ShortenRequestValidation*"

# Manual checks
curl -X POST http://localhost/shorten -d '{"longUrl":"javascript:alert(1)"}' -H 'Content-Type: application/json'
# -> 400

curl -X POST http://localhost/shorten -d '{"longUrl":"HTTPS://example.com"}' -H 'Content-Type: application/json'
# -> 200 (case-insensitive scheme accepted)

curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com/<bad>"}' -H 'Content-Type: application/json'
# -> 400 (URI-illegal character rejected, never persisted)
```

---

## PR 2 — Non-root containers + secret management (#16)

### What was built
`USER appuser` added to all four service Dockerfiles (previously all ran as root by default);
Compose credentials (Scylla/Redis) moved out of plaintext into a `.env`/`.env.secrets` split
mirroring the k8s chart's existing `ConfigMap`/`Secret` pattern; the chart's existing
`secret.yaml` verified actually wired into the relevant Deployments. Full compose stack verified
end-to-end (~27 minutes of real integration testing) to start healthy with every container
running as the non-root user.

### Decisions & trade-offs
- **Incident (not a bug):** a one-off `ENOTFOUND` network error right after branch setup, no
  work at risk. Recovered by resuming the same worker.
- No review findings — shipped clean on the first pass.

### How to test
```bash
# Confirm no container runs as root
docker compose up -d
for svc in shortener-service resolver-service keygen-service config-server; do
  echo "$svc: $(docker compose exec $svc whoami)"
done
# None should print "root"

# Confirm no plaintext credential in the repo
grep -rn "password" docker-compose.yml   # should show only ${VAR} references, not literals
```

---

## PR 3 — API key authentication (#19, plus support work #17/#18)

### What was built
API key auth on `/shorten` only (`/resolve` stays public — a URL shortener's whole purpose is
public redirects). Keys stored hashed (SHA-256), validated via an `ApiKeyFilter`. This PR went
through the longest review chain of the whole roadmap — see below.

### Decisions & trade-offs (in order)
1. **Bug — dev-key fallback:** `frontend/src/lib/api.ts` fell back to a literal committed dev
   key when `NEXT_PUBLIC_API_KEY` was unset, so a misconfigured production build would silently
   authenticate with a key published in `.env.example`/README. **Fixed:** removed the fallback.
2. **Simplification — unnecessary OPTIONS bypass:** `ApiKeyFilter`'s preflight bypass was dead
   code (the gateway already terminates all CORS preflight for `/shorten`) and widened the
   attack surface for a direct-to-service request. **Fixed:** removed.
3. **Escalated — public browser-held key:** the initial frontend wiring read the API key via
   `NEXT_PUBLIC_API_KEY`, which Next.js inlines into the browser bundle — readable by anyone via
   devtools, defeating the point of the key. **Captain decided:** build a server-side Next.js
   proxy route holding the real key server-side; the browser bundle carries no key at all.
4. **Escalated — chart ships a working dev key by default:** `values.yaml` defaulted
   `shortenerApiKeyHashes` to the hash of the published dev key, so an unconfigured `helm
   install` looked gated but wasn't. **Fixed:** default to empty, forcing an explicit override
   (the filter already fails closed on an empty hash list).
5. **Escalated — the new proxy route was itself unauthenticated and unrate-limited:**
   anyone could hit the frontend's `/api/shorten` directly and get unlimited authenticated
   writes through the server-held key, and it collapsed nginx's existing per-IP rate limit
   (every browser user now shared one bucket, since traffic looked like it came from one Next.js
   server IP). **Captain decided:** add a rate limit on the proxy route.
6. **Escalated — the rate limiter was keyed on a spoofable header:** the limiter trusted
   `X-Forwarded-For`, which nothing in the repo actually sets — an attacker could send a random
   value per request and get a fresh bucket every time. **Captain decided:** key on the real
   connection address instead.
7. **Escalated — Next.js 16's App Router has no connection-IP API at all:** `request.ip` was
   removed, so the "real connection address" fallback became a single site-wide shared bucket —
   one attacker looping requests could 429 every other user. **Captain decided:** push for a
   real fix — move rate limiting to nginx (which does see the real TCP connection), requiring
   the frontend to be deployed behind the gateway for the first time.
8. **Bug — that deployment surface change was flagged as scope creep**, but review confirmed it
   was the direct, necessary consequence of decision #7 (frontend Dockerfile, compose service,
   k8s Ingress/Deployment) — confirmed intentional, kept.
9. **Bug — static-asset requests shared the write-path rate limit:** putting the frontend
   behind the gateway meant a normal page load's burst of `/_next/static/...` chunks tripped the
   5r/s limit meant for `/shorten`. **Fixed:** exempted static asset paths from the limiter.
10. **Bug — that exemption regex was unanchored:** it matched any URI ending in a static-asset
    extension *anywhere on the server*, potentially letting an attacker dodge the `/shorten`
    limit via a crafted path. **Fixed:** anchored to the frontend's actual known asset paths only.
11. **Bug — plaintext key leaked to every backend pod:** the key ended up in the shared
    `hopr-secret`, consumed wholesale via `envFrom` by `keygen-service`/`resolver-service` too,
    which never needed it. **Fixed:** scoped to a frontend-only Secret.
12. **Operational blocker — no deterministic test command:** the pipeline's test step kept
    re-exploring the whole change from scratch and hitting a 30-minute cap, because this repo
    had no `.no-mistakes.yaml` configuring a test command. **Fixed via a separate PR (#18)**
    landing `.no-mistakes.yaml` on `main` first, since repo config is only read from the default
    branch — this fixes every future PR's test step, not just this one.
13. **Escalated — destructive verification step:** the k8s ingress fix needed live proof the
    cluster actually serves the minted cert, requiring recreating the local `kind` cluster (a
    12-day-old cluster with other workloads outside this task's scope). **Captain authorized**
    the recreation explicitly.

### How to test
```bash
./gradlew :shortener-service:test --tests "*ApiKey*"

# /shorten requires a key; /resolve does not
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json'
# -> 401 (no key)
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' -H 'X-API-Key: <your-dev-key>'
# -> 200
curl -I http://localhost/<shortKey>
# -> 307 (no key needed)

# Confirm the browser bundle carries no key
curl -s http://localhost:3000/_next/static/chunks/*.js | grep -c "hopr-local-dev-key" # should be 0

# Confirm rate limiting on the proxy route (nginx-level)
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:3000/api/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json'; done
# Expect some 429s after the configured burst.
```

---

## PR 4 — TLS termination (#20)

### What was built
Self-signed TLS at the gateway for local dev (real CA certs require a domain this project
doesn't have — flagged as a future concept for a real deployment). nginx listens on 443, plain
HTTP (80) redirects to HTTPS, HSTS + security headers added. k8s Ingress wired with an
equivalent self-signed Secret and `force-ssl-redirect`.

### Decisions & trade-offs
- **Bug — HSTS pinned `localhost` for a year in the developer's real browser:** a year-long
  `max-age` on a local dev cert would pin `localhost` HTTPS-only in the browser's actual HSTS
  store, breaking unrelated plain-HTTP local projects on the same machine, clearable only via
  `chrome://net-internals`. **Fixed:** short dev-only `max-age` (300s); a real deployment would
  use the standard one-year value.
- **Simplification — unexercised optional-TLS chart branch:** `values.yaml` had an
  empty-default/conditional TLS Secret rendering, documented as "falls back to the controller's
  default cert" — but `deploy.sh` always passes the real cert, so nothing exercised the skip
  path. **Fixed:** removed the conditional, render unconditionally.
- **Kept the general hardening headers:** `X-Content-Type-Options`/`X-Frame-Options`/
  `Referrer-Policy` were flagged as beyond the narrow "TLS" scope; kept anyway since they're
  harmless and fit Phase 3's overall theme.
- **Escalated — real dev flow broken over TLS:** the host-run `npm run dev` flow's
  `.env.example` still pointed at the now-redirect-only HTTP port, and even fixing the URL hit a
  self-signed-cert rejection in Node's fetch. **Captain decided:** keep the host dev flow going
  through the real gateway and trust the dev cert via `NODE_EXTRA_CA_CERTS`, rather than
  bypassing the gateway.
- **Bug — the local k8s HTTP entry point was a dead end:** `kind`'s port mapping meant
  `localhost:8888` (HTTP) redirected to itself instead of the actual working HTTPS port
  (`8443`), since the redirect reused the incoming `Host` header's port. **Fixed:** retargeted
  the redirect at the real HTTPS port rather than dropping the HTTP entry point.
- **Bug — an inert cert-manager scaffold contradicted the brief:** the chart kept unused
  per-Ingress `tls:` blocks "for a future cert-manager setup," which the original brief
  explicitly said not to build. **Fixed:** removed; the Secret itself stays, referenced by the
  controller's `--default-ssl-certificate` flag.
- **Escalated — verification required destroying an existing cluster:** proving the k8s ingress
  actually serves the minted cert (not the controller's default fake cert) required recreating
  the local `kind` cluster. **Captain authorized** it explicitly; verified afterward via
  `openssl s_client`.

### How to test
```bash
# Generate the local dev cert (never committed)
./api-gateway/generate-dev-cert.sh

# Confirm HTTP redirects to HTTPS
curl -I http://localhost/  # expect 301 to https://

# Confirm the served cert is the minted one, not a default
echo | openssl s_client -connect localhost:443 2>/dev/null | openssl x509 -noout -subject -issuer
# subject should show "O=Hopr local development"

# k8s: same check against the kind cluster's mapped HTTPS port
echo | openssl s_client -connect localhost:8443 2>/dev/null | openssl x509 -noout -subject -issuer
```
