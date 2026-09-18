// Server-only: the API key never reaches the browser. A NEXT_PUBLIC_ variable is inlined into
// the client bundle and readable by anyone who opens devtools, which would make the key
// pointless as an abuse control -- so the browser posts here and this route adds the header.

const UPSTREAM_BASE_URL =
  process.env.SHORTENER_API_BASE_URL?.replace(/\/$/, "") ?? "http://hopr.localhost:80";

export async function POST(request: Request): Promise<Response> {
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
