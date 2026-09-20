# Kafka event schemas

Fixture files here are the single source of truth for a Kafka message shape published by
one service and consumed by another (today: this PR's own producer-side tests; from the
next PR in the Phase 5 queue onward, also a consumer). Each fixture is read verbatim by a
test on every side that touches the shape it names, the same pattern `docs/contracts/`
uses for HTTP request/response bodies — see that directory's README for why a shared
fixture plus ordinary JUnit tests is enough here instead of a schema registry or a
framework like Pact.

- `url-created-event.json` — `UrlCreatedEvent` (`com.pcaokhai.common.event.UrlCreatedEvent`),
  published to the `url-created` topic by `shortener-service`'s `CachePrimePoller` /
  `UrlCreatedEventPublisher` for every short link that's durably persisted. Asserted against
  by `shortener-service`'s `UrlCreatedEventPublisherIntegrationTest`.
- `url-clicked-event.json` — `ClickEvent` (`com.pcaokhai.common.event.ClickEvent`), published
  to the `url-clicked` topic by `resolver-service`'s `ClickEventPublisher` after every
  successful redirect. Asserted against by `resolver-service`'s
  `ClickEventPublisherIntegrationTest`.

## Versioning

Both DTOs carry an `eventVersion` integer field (currently `1` for both). Bump it whenever
a change to the record would be incompatible for an existing consumer (removing/renaming a
field, changing a field's meaning); purely additive fields don't require a bump. A consumer
should branch on `eventVersion` rather than assume the current shape.

## Delivery guarantees

- **`UrlCreatedEvent`** is published from the outbox mechanism added in the prior PR
  (`shortener-service`'s `CachePrimePoller`, `docs/events/../../shortener-service/.../infra/outbox/`):
  the fact that a short link was created is durably written to the `outbox_events` table in
  the same request that creates the `urls` row, *before* any Kafka publish is attempted. If
  the publish fails, the event stays logged as a failure but the outbox row is still marked
  processed (cache-priming, which the same poll loop performs, must not be held hostage by a
  Kafka outage) — see the PR description for the full trade-off this accepts.
- **`ClickEvent`** is published directly from `resolver-service`'s redirect path with no
  outbox record behind it, fire-and-forget: a redirect doesn't otherwise write anything
  durable, so there's nothing to hang an outbox row on without inventing a lighter-weight
  durability mechanism just for clicks. A Kafka outage is logged and never blocks, delays, or
  fails the 307 response. This is a best-effort, eventually-consistent guarantee, which is an
  acceptable trade-off for click analytics (a few lost clicks under a Kafka outage degrade a
  dashboard, not the product) in a way it would not be for the URL mapping itself (a lost
  `UrlCreated` event would mean a link nobody's analytics or downstream systems ever learn
  exists, even though the link itself keeps working).
