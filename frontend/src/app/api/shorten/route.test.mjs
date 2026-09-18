import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";

const realFetch = globalThis.fetch;

function stubFetch(response) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return typeof response === "function" ? response() : response;
  };
  return calls;
}

beforeEach(() => {
  process.env.SHORTEN_API_KEY = "test-server-key";
  process.env.SHORTENER_API_BASE_URL = "http://gateway.test";
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

// Each test loads its own module instance, so the in-memory rate-limit buckets start empty.
function loadRoute() {
  return import("./route.ts?" + Math.random());
}

function request(body, ip) {
  return new Request("http://localhost:3000/api/shorten", {
    method: "POST",
    headers: ip
      ? { "Content-Type": "application/json", "x-forwarded-for": ip }
      : { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function post(body) {
  const { POST } = await loadRoute();
  return POST(request(body));
}

test("attaches the server-only API key to the upstream request", async () => {
  const calls = stubFetch(Response.json({ shortUrl: "http://hopr.localhost/abc" }, { status: 201 }));

  const res = await post({ longUrl: "https://example.com" });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "http://gateway.test/shorten");
  assert.equal(new Headers(calls[0].init.headers).get("X-API-Key"), "test-server-key");
  assert.deepEqual(JSON.parse(calls[0].init.body), { longUrl: "https://example.com" });
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), { shortUrl: "http://hopr.localhost/abc" });
});

test("relays an upstream error status and body unchanged", async () => {
  stubFetch(Response.json({ status: 409, message: "alias taken" }, { status: 409 }));

  const res = await post({ longUrl: "https://example.com", alias: "taken" });

  assert.equal(res.status, 409);
  assert.deepEqual(await res.json(), { status: 409, message: "alias taken" });
});

test("fails with 500 rather than an unauthenticated call when the key is unset", async () => {
  delete process.env.SHORTEN_API_KEY;
  const calls = stubFetch(Response.json({}, { status: 201 }));

  const res = await post({ longUrl: "https://example.com" });

  assert.equal(res.status, 500);
  assert.equal(calls.length, 0);
});

test("reports an unreachable backend as 502", async () => {
  globalThis.fetch = async () => {
    throw new TypeError("fetch failed");
  };

  const res = await post({ longUrl: "https://example.com" });

  assert.equal(res.status, 502);
});

test("rate limits a flood and cannot be bypassed by spoofing forwarding headers", async () => {
  const calls = stubFetch(() => Response.json({ shortUrl: "http://hopr.localhost/abc" }, { status: 201 }));
  const { POST } = await loadRoute();
  const body = { longUrl: "https://example.com" };

  const responses = [];
  for (let i = 0; i < 12; i++) {
    // A fresh spoofed address per request: it must not mint a fresh bucket.
    responses.push(await POST(request(body, `10.0.0.${i}`)));
  }

  const rejected = responses.filter((res) => res.status === 429);
  assert.ok(rejected.length > 0, "a burst of 12 requests should trip the limit");
  assert.deepEqual(await rejected[0].json(), {
    status: 429,
    message: "Too many requests — please slow down and try again.",
  });
  assert.equal(
    calls.length,
    responses.length - rejected.length,
    "rejected requests must not reach upstream",
  );

  // Nor does dropping the header entirely.
  assert.equal((await POST(request(body))).status, 429);
});

test("refills over time so a paced caller is not blocked", async () => {
  stubFetch(() => Response.json({ shortUrl: "http://hopr.localhost/abc" }, { status: 201 }));
  const { POST } = await loadRoute();
  const body = { longUrl: "https://example.com" };

  for (let i = 0; i < 12; i++) await POST(request(body));
  await new Promise((resolve) => setTimeout(resolve, 400));

  assert.equal((await POST(request(body))).status, 201);
});
