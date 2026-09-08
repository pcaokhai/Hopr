const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://hopr.localhost:80";

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
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(alias ? { longUrl, alias } : { longUrl }),
    });
  } catch {
    throw new ApiError(0, "Could not reach the Hopr API. Is the backend running?");
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
