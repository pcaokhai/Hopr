# Phase 5 — Event-Driven Architecture

**Goal:** keep the hot redirect path fast and simple while still reliably capturing everything
needed for analytics — using the outbox pattern and Kafka rather than dual writes.

## PR 1 — Outbox pattern (#25)

### What was built
`shortener-service`'s create flow previously did two independent, non-transactional writes:
persist to ScyllaDB, then prime the Redis cache. A crash between the two left the mapping
persisted but the cache unprimed (not a correctness bug — the resolver falls back to Scylla on
cache miss — but exactly the dual-write inconsistency this phase exists to eliminate before
Kafka publishing makes the same problem worse). Added an `outbox_events` table (Flyway `V5`)
written as part of the same atomic operation as the URL insert, and a poller
(`CachePrimePoller`) that reads unprocessed outbox records and reliably primes the cache,
marking each processed.

### Decisions & trade-offs
- Clean pass, no review findings. Tested against real Scylla + Redis via Testcontainers,
  including a simulated crash-between-persist-and-cache-prime scenario proving the poller
  recovers.
- **Extensibility point (by design):** built so Phase 5 PR 2 (Kafka publishing) could consume
  the same outbox table as an additional consumer, without this PR needing to know about Kafka.

### How to test
```bash
./gradlew :shortener-service:test --tests "*Outbox*"

# Manual: verify cache gets primed after create even under simulated poller delay
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' -H 'X-API-Key: <key>'
docker compose exec redis-node-1 redis-cli GET <shortKey>   # should eventually show the mapping
```

---

## PR 2 — Kafka event publishing (#26)

### What was built
A Kafka broker added to the stack. `UrlCreated` events published from `shortener-service` via
the same outbox mechanism from PR 1 (durable, reliable). `UrlClicked` events published directly
from `resolver-service` on a successful redirect — fire-and-forget, since there's no
pre-existing durable write on the redirect path to hang an outbox record on; a publish failure
is logged, never allowed to block or fail the actual redirect.

### Decisions & trade-offs
- **Asymmetry named explicitly (by design):** `UrlCreated` gets outbox-backed durability;
  `UrlClicked` gets best-effort delivery. This is intentional — analytics data (clicks) is a
  natural fit for eventually-consistent, best-effort delivery in a way the URL mapping itself
  is not.
- One transient pipeline pause (worker briefly parked at a gate mid-review, resolved by a
  direct nudge) — not a defect, just a supervision hiccup.

### How to test
```bash
./gradlew :shortener-service:test :resolver-service:test --tests "*Kafka*"

# Manual: confirm events actually publish
docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic url-created --from-beginning &
curl -X POST http://localhost/shorten -d '{"longUrl":"https://example.com"}' -H 'Content-Type: application/json' -H 'X-API-Key: <key>'
# Should see the UrlCreated event appear on the consumer.

# Confirm a Kafka outage doesn't break the redirect
docker compose stop kafka
curl -I http://localhost/<shortKey>   # should still 307 successfully
docker compose start kafka
```

---

## PR 3 — Click-analytics consumer (#27)

### What was built
A new, minimal `click-analytics-service` consuming `UrlClicked` events and writing to
`url_click_counts` (counter increment) and `url_click_events` (event log, partitioned by
`(short_key, day)`) — both tables existed unused since Phase 1. Fully separate from the hot
redirect path, so analytics writes never add latency or failure risk to serving a redirect.

### Decisions & trade-offs
- **Design choice:** a new lightweight service rather than a component bolted onto an existing
  one — keeps deployment/scaling independent of the hot-path services, matching the "separate
  consumer" language in the original review.
- **Named trade-off (by design):** Kafka's default at-least-once delivery means a click could
  theoretically be double-counted on redelivery. No new dedup machinery was built for this PR;
  the risk is named rather than engineered around, since a natural dedup key wasn't readily
  available without disproportionate new complexity.
- Clean pass, no review findings.

### How to test
```bash
./gradlew :click-analytics-service:test

# Manual: click a link and confirm the count increments
curl -I http://localhost/<shortKey>  # triggers a click
sleep 2  # allow consumer to process
docker compose exec scylla-node-1 cqlsh -e "SELECT * FROM hopr.url_click_counts WHERE short_key='<shortKey>';"
docker compose exec scylla-node-1 cqlsh -e "SELECT * FROM hopr.url_click_events WHERE short_key='<shortKey>' AND day=toDate(now());"
```
