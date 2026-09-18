# Hopr frontend

Next.js (App Router) + TypeScript + Tailwind + shadcn/ui + Zustand.

## Running against the local backend

```bash
# from the repo root
docker compose up -d

# from this directory
npm install
npm run dev
```

Open http://localhost:3000. The shorten box on the landing page posts to this
app's own server-side route (`src/app/api/shorten/route.ts`), which forwards to
the gateway at `SHORTENER_API_BASE_URL` (default `http://hopr.localhost:80`)
with the `SHORTEN_API_KEY` attached as `X-API-Key`. Both are server-only
variables — see `.env.example` and copy it to `.env.local` to override. The key
is deliberately not `NEXT_PUBLIC_`: that would inline it into the browser bundle
where anyone can read it. Generated short
links point at `http://hopr.localhost`, not the Next.js dev server.

## What's real vs. mocked

**Real, wired to the backend:**
- Landing page shorten form (`POST /shorten`) — long URL + optional custom
  slug, returns a real short link with copy-to-clipboard and a client-side
  QR code.

**Mocked (backend has no list/analytics/auth endpoints yet):**
- `/dashboard` — link list, search, "Create link" modal. Backed by a Zustand
  store seeded with fake links (`src/lib/mock-data.ts`,
  `src/lib/dashboard-store.ts`).
- `/dashboard/[slug]` — clicks-over-time chart, top countries/devices/referrers.
  All fabricated per-slug in `mockAnalyticsFor`.
- There's no real login — the dashboard is open, with a small note that it's
  a demo view. Every mocked screen carries a "Demo data · not wired to
  backend" badge (`src/components/mock-badge.tsx`).

Wire these up for real once the backend grows list/analytics/auth endpoints.

## Tests

```bash
npm test
```

Covers the alias-validation regex (mirrored from the shortener-service, see
`src/lib/alias.ts`) and the `/api/shorten` proxy route — that the API key is
attached server-side, that the upstream status and body are relayed unchanged,
and that the browser-side `shorten()` sends no key of its own.
