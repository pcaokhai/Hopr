// Server-only: the API key never reaches the browser. A NEXT_PUBLIC_ variable is inlined into
// the client bundle and readable by anyone who opens devtools, which would make the key
// pointless as an abuse control -- so the browser posts here and this route adds the header.
export const dynamic = "force-dynamic";

const UPSTREAM_BASE_URL =
  process.env.SHORTENER_API_BASE_URL?.replace(/\/$/, "") ?? "http://hopr.localhost:80";

// This route holds the API key, so without a limit it would be an unmetered way into /shorten:
// the gateway's per-IP zone only ever sees this server's address. Token bucket matching nginx's
// `rate=5r/s burst=10`, keyed on the caller's IP.
// ponytail: per-process and in-memory, so this is only correct while the frontend runs as a
// single instance; multiple replicas need a shared store (Redis) instead.
const REFILL_PER_SECOND = 5;
const BURST = 10;

const buckets = new Map<string, { tokens: number; updatedAt: number }>();

function clientIp(request: Request): string {
  const forwarded = request.headers.get("x-forwarded-for");
  if (forwarded) return forwarded.split(",")[0].trim();
  return request.headers.get("x-real-ip") ?? "unknown";
}

function allow(ip: string, now = Date.now()): boolean {
  const bucket = buckets.get(ip) ?? { tokens: BURST, updatedAt: now };
  const refilled = Math.min(BURST, bucket.tokens + ((now - bucket.updatedAt) / 1000) * REFILL_PER_SECOND);
  if (refilled < 1) {
    buckets.set(ip, { tokens: refilled, updatedAt: now });
    return false;
  }
  buckets.set(ip, { tokens: refilled - 1, updatedAt: now });
  return true;
}

export async function POST(request: Request): Promise<Response> {
  if (!allow(clientIp(request))) {
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
