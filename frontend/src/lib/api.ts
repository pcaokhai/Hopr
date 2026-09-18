const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://hopr.localhost:80";

// POST /shorten is API-key gated. NEXT_PUBLIC_ means this key ships to the browser and is
// therefore public -- it identifies this client for rate limiting and revocation, it does not
// keep anyone out. A deployment that needs a real secret has to call /shorten from a server-side
// route holding a non-public key, not from here.
const API_KEY = process.env.NEXT_PUBLIC_API_KEY ?? "hopr-local-dev-key";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export interface ShortenResponse {
  shortUrl: string;
}

export async function shorten(longUrl: string, alias?: string): Promise<ShortenResponse> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}/shorten`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-API-Key": API_KEY },
      body: JSON.stringify(alias ? { longUrl, alias } : { longUrl }),
    });
  } catch {
    throw new ApiError(0, "Could not reach the Hopr API. Is the backend running?");
  }

  if (res.status === 401) {
    throw new ApiError(401, "This client is not authorised to shorten URLs (missing or invalid API key).");
  }

  if (res.status === 429) {
    throw new ApiError(429, "Too many requests — please slow down and try again.");
  }

  const body = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(body?.status ?? res.status, body?.message ?? "Something went wrong.");
  }
  return body as ShortenResponse;
}
