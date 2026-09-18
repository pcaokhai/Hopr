// Server-only: the API key never reaches the browser. A NEXT_PUBLIC_ variable is inlined into
// the client bundle and readable by anyone who opens devtools, which would make the key
// pointless as an abuse control -- so the browser posts here and this route adds the header.
export const dynamic = "force-dynamic";

const UPSTREAM_BASE_URL =
  process.env.SHORTENER_API_BASE_URL?.replace(/\/$/, "") ?? "http://hopr.localhost:80";

// This route holds the API key, so without a limit it would be an unmetered way into /shorten:
// the gateway's per-IP zone only ever sees this server's address. Token bucket matching nginx's
// `rate=5r/s burst=10`.
//
// The bucket is process-wide, not per caller, because a route handler in this Next.js version is
// handed no connection address (the request proxy returns undefined for `ip`, and Next only fills
// `x-forwarded-for` in when the caller did not send one). Keying on a caller-controlled header
// would let an attacker mint a fresh full bucket per request and bypass the limit entirely, so a
// single shared bucket is the fail-closed choice. Revisit this if a real reverse proxy is ever
// put in front of the frontend: the connection address would then always be the proxy's, and the
// correct form becomes a trusted x-forwarded-for with a configured trusted-proxy hop count.
// ponytail: per-process and in-memory, so this is only correct while the frontend runs as a
// single instance; multiple replicas need a shared store (Redis) instead.
const REFILL_PER_SECOND = 5;
const BURST = 10;

let tokens = BURST;
let updatedAt = Date.now();

function allow(now = Date.now()): boolean {
  tokens = Math.min(BURST, tokens + ((now - updatedAt) / 1000) * REFILL_PER_SECOND);
  updatedAt = now;
  if (tokens < 1) return false;
  tokens -= 1;
  return true;
}

export async function POST(request: Request): Promise<Response> {
  if (!allow()) {
    return Response.json(
      { status: 429, message: "Too many requests — please slow down and try again." },
      { status: 429 },
    );
  }

  const apiKey = process.env.SHORTEN_API_KEY;
  if (!apiKey) {
    return Response.json(
      { status: 500, message: "SHORTEN_API_KEY is not configured on the server." },
      { status: 500 },
    );
  }

  const body = await request.text();

  let upstream: Response;
  try {
    upstream = await fetch(`${UPSTREAM_BASE_URL}/shorten`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-API-Key": apiKey },
      body,
    });
  } catch {
    return Response.json(
      { status: 502, message: "Could not reach the Hopr API. Is the backend running?" },
      { status: 502 },
    );
  }

  const upstreamBody = await upstream.text();
  return new Response(upstreamBody, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
  });
}
