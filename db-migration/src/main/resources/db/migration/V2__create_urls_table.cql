-- Primary read path: "given a short key, what is the long URL?" — a single-row
-- lookup by partition key, which is the only access pattern a wide-column store
-- serves cheaply. Everything else this table could be asked (all URLs for an
-- owner, URLs expiring soon) would need its own query-first table.
CREATE TABLE IF NOT EXISTS hopr.urls (
    short_key  text PRIMARY KEY,
    long_url   text,
    alias      text,
    owner_id   text,
    status     text,
    created_at timestamp,
    expires_at timestamp
);
