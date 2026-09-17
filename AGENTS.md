## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.

## Frontend

`frontend/` is a Next.js (App Router) + TypeScript + Tailwind + shadcn/ui + Zustand app. See `frontend/README.md`
for how to run it and exactly which screens are wired to the real `/shorten` API vs. backed by mock data
(the backend has no list/analytics/auth endpoints yet). shadcn/ui here uses the Base UI component library
(not Radix) — components use the `render` prop for polymorphism, not `asChild`, and a `Button` wrapping a
non-native element (e.g. a `next/link`) needs `nativeButton={false}` or Base UI logs an a11y warning.

## Database schema

ScyllaDB schema lives in `db-migration/` as Flyway CQL migrations — see `db-migration/README.md`
for how to run them and for the Scylla/Flyway constraints (counters vs. tablets, no semicolons in
CQL comments) that will bite anyone editing a migration. Never apply DDL by hand via `cqlsh`.
The services read and write it through Spring Data Cassandra (`common`'s `UrlMapping`/`UrlRepository`);
MongoDB is gone. `UrlMapping`'s CQL column names are spelled out because the table is snake_case.
Every production write of `urls` claims its short key through the `IF NOT EXISTS` lightweight
transaction in the shortener's `DbCacheSaver.saveUrlMappingIfAbsent` — a non-applied write becomes
a 409 for a user-chosen alias and a bounded retry under a freshly generated key otherwise.
`UrlRepository.save` is a plain CQL INSERT, i.e. an upsert that silently overwrites an existing
row, and is not used to write.

## Resilience

Spring Boot 4 ships no AOP starter and nothing puts AspectJ on the classpath, so Resilience4j's
`@CircuitBreaker`/`@Retry` annotations are inert here — the aspect beans never register. Apply
Resilience4j programmatically through the autoconfigured `CircuitBreakerRegistry` instead (see
`shortener-service`'s `KeyGenClient`). Thresholds live in `config-server/src/main/resources/config-repo/`.
