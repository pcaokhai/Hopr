# Hopr frontend

Next.js (App Router) + TypeScript + Tailwind + shadcn/ui + Zustand.

## Running against the local backend

```bash
# from the repo root
./api-gateway/generate-dev-cert.sh   # once; the gateway needs it to serve HTTPS
docker compose up -d

# from this directory
npm install

# trust the gateway's dev certificate, or Node rejects it as self-signed
export NODE_EXTRA_CA_CERTS="$(cd .. && pwd)/api-gateway/certs/dev.crt"
npm run dev
```

Generate the certificate before either step — the gateway will not start without it.
`NODE_EXTRA_CA_CERTS` must be an absolute path (or relative to the directory you run
`npm run dev` from) and must be exported in the same shell as `npm run dev`.

Open http://localhost:3000. The shorten box on the landing page posts to this
app's own server-side route (`src/app/api/shorten/route.ts`), which forwards to
the gateway at `SHORTENER_API_BASE_URL` (default `https://hopr.localhost`)
with the `SHORTEN_API_KEY` attached as `X-API-Key`. Both are server-only
variables — see `.env.example` and copy it to `.env.local` to override. The key
is deliberately not `NEXT_PUBLIC_`: that would inline it into the browser bundle
where anyone can read it. Because that route fronts the key, it is served from behind
the Nginx gateway, which rate-limits it per real client address (5r/s, burst 10)
— a Next.js route handler is given no connection address of its own, so it
cannot limit per caller. `api-gateway/rate-limit-test.sh` proves that end of it. Generated short
links point at `https://hopr.localhost`, not the Next.js dev server.

## What's real vs. mocked

**Real, wired to the backend:**
- Landing page shorten form (`POST /v1/shorten`) — long URL + optional custom
  slug, returns a real short link with copy-to-clipboard and a client-side
  QR code.

**Mocked (not yet wired to the backend):**
- `/dashboard` — link list, search, "Create link" modal. Backed by a Zustand
  store seeded with fake links (`src/lib/mock-data.ts`,
  `src/lib/dashboard-store.ts`). `shortener-service` now exposes
  `GET/PATCH/DELETE /v1/links` (see the root `AGENTS.md`'s "Link management"
  section) but this UI hasn't been switched over to it.
- `/dashboard/[slug]` — clicks-over-time chart, top countries/devices/referrers.
  All fabricated per-slug in `mockAnalyticsFor`; there is still no analytics
  endpoint.
- There's no real login — the dashboard is open, with a small note that it's
  a demo view. There is still no auth endpoint beyond the shared `X-API-Key`.
  Every mocked screen carries a "Demo data · not wired to backend" badge
  (`src/components/mock-badge.tsx`).

Wire the dashboard list/detail views up to `/v1/links` and add analytics/auth
endpoints before removing the mock badge.

## Tests

```bash
npm test
```

Covers the alias-validation regex (mirrored from the shortener-service, see
`src/lib/alias.ts`) and the `/api/shorten` proxy route — that the API key is
attached server-side, that the upstream status and body are relayed unchanged,
that the gateway's 429 is relayed to the caller,
and that the browser-side `shorten()` sends no key of its own.
