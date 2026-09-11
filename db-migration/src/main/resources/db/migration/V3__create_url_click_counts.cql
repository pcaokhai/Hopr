-- Counters must live in their own table: Scylla forbids mixing counter and
-- non-counter columns, because a counter update is a commutative increment
-- replicated differently from a normal last-write-wins cell. That is also why
-- `clicks` cannot simply be a bigint column on `urls` — concurrent redirects
-- would read-modify-write over each other and lose counts.
CREATE TABLE IF NOT EXISTS hopr.url_click_counts (
    short_key text PRIMARY KEY,
    clicks    counter
);
