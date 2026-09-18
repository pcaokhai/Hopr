import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { shorten } from "./api.ts";

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

test("posts to the same-origin proxy and sends no API key from the browser", async () => {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return Response.json({ shortUrl: "http://hopr.localhost/abc" }, { status: 201 });
  };

  const result = await shorten("https://example.com");

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/shorten");
  const headers = new Headers(calls[0].init.headers);
  assert.equal(headers.get("X-API-Key"), null);
  assert.deepEqual([...headers.keys()], ["content-type"]);
  assert.deepEqual(result, { shortUrl: "http://hopr.localhost/abc" });
});
