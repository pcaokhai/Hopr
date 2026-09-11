-- Raw click events, partitioned by (short_key, day) rather than short_key alone.
-- A viral link partitioned only by short_key would grow without bound into one hot
-- partition pinned to one replica set. Bucketing by day caps each partition at a
-- single day of traffic and spreads load across the ring as days advance.
-- Clustering by clicked_at DESC makes "most recent clicks first" a sequential read
-- off disk with no sort.
--
-- Note: keep semicolons out of these comments. Flyway's CQL parser splits statements
-- on the statement terminator without first stripping line comments, so a semicolon
-- inside a comment truncates the statement and the cluster rejects it as a syntax error.
CREATE TABLE IF NOT EXISTS hopr.url_click_events (
    short_key  text,
    day        date,
    clicked_at timestamp,
    referrer   text,
    user_agent text,
    country    text,
    PRIMARY KEY ((short_key, day), clicked_at)
) WITH CLUSTERING ORDER BY (clicked_at DESC);
