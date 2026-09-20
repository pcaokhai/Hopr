-- Outbox for shortener-service's create flow: "did we persist the url row, and do we
-- still owe a downstream step (today: priming the Redis cache) a look at it?" The table
-- is modeled around the poller's read, not as a generic log: the poller's only query is
-- "give me today's (and yesterday's, for stragglers near midnight) still-PENDING events,
-- oldest first" -- so status sits in the partition key alongside the day bucket, letting
-- that query run as a single-partition read with no ALLOW FILTERING or secondary index.
--
-- Bucketing by day bounds partition size the same way V4's url_click_events does; a single
-- fixed bucket would grow into one hot partition pinned to one replica set as volume grows.
--
-- Marking an event processed moves it from partition (bucket, 'PENDING') to
-- (bucket, 'PROCESSED') -- a cross-partition delete+insert, which is fine in an unconditional
-- (non-LWT) batch. It cannot be done as an UPDATE, because status is part of the partition key.
--
-- Note: keep semicolons out of these comments -- see V4's note on Flyway's CQL parser.
CREATE TABLE IF NOT EXISTS hopr.outbox_events (
    bucket       date,
    status       text,
    created_at   timestamp,
    event_id     timeuuid,
    short_key    text,
    event_type   text,
    processed_at timestamp,
    PRIMARY KEY ((bucket, status), created_at, event_id)
) WITH CLUSTERING ORDER BY (created_at ASC, event_id ASC);
