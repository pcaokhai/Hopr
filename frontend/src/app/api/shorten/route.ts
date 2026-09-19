// Server-only: the API key never reaches the browser. A NEXT_PUBLIC_ variable is inlined into
// the client bundle and readable by anyone who opens devtools, which would make the key
// pointless as an abuse control -- so the browser posts here and this route adds the header.

const UPSTREAM_BASE_URL =
  process.env.SHORTENER_API_BASE_URL?.replace(/\/$/, "") ?? "https://hopr.localhost";

// The payload is a URL plus an optional alias; anything larger is abuse, and buffering it whole
// would let a single request exhaust the frontend process.
const MAX_BODY_BYTES = 8 * 1024;

async function readBoundedBody(request: Request): Promise<string | null> {
  const reader = request.body?.getReader();
  if (!reader) {
    return "";
  }
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    size += value.byteLength;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const joined = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(joined);
}

export async function POST(request: Request): Promise<Response> {
  const apiKey = process.env.SHORTEN_API_KEY;
  if (!apiKey) {
    return Response.json(
      { status: 500, message: "SHORTEN_API_KEY is not configured on the server." },
      { status: 500 },
    );
  }

  const body = await readBoundedBody(request);
  if (body === null) {
    return Response.json(
      { status: 413, message: "Request body is too large." },
      { status: 413 },
    );
  }

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
