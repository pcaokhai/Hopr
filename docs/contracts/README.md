# Inter-service contracts

Fixture files here are the single source of truth for a request/response shape shared
across two service modules under different Gradle projects. Each fixture is read by a
test on both sides of the boundary:

- `keygen-generate-response.json` — `keygen-service`'s `GET /generate` response.
  Asserted verbatim by `keygen-service`'s `KeyGenControllerContractTest` (provider side)
  and fed into `shortener-service`'s `KeyGenClientContractTest` (consumer side) as the
  mocked HTTP response body. If either service's code changes what this JSON looks like
  without updating the fixture (or updating it without changing the other side to match),
  one of the two tests fails.

This is a hand-written consumer/provider fixture, not a framework like Pact or Spring
Cloud Contract — both services live in this repo, are owned by the same team, and the
contract is a single small JSON object, so a shared fixture plus two ordinary JUnit tests
gives the same guarantee (a breaking shape change is caught before it reaches production)
without the extra build tooling, stub-generation step, or contract broker a dedicated
framework would add for two internal Java services.
