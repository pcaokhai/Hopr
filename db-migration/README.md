# db-migration

Schema-as-code for Hopr's ScyllaDB keyspace. The versioned CQL files in
`src/main/resources/db/migration` are the only place the schema is defined — every future
change (adding `expires_at` semantics, owner/tenant columns, a new query table) is a new
`V<n>__description.cql` file, never hand-run `cqlsh` DDL.

## Running the migrations

Bring the cluster up and apply everything:

```bash
docker compose up -d scylla-node-1 scylla-node-2 scylla-node-3
./gradlew :db-migration:migrateScylla
```

Against a different cluster:

```bash
./gradlew :db-migration:migrateScylla \
  -Pscylla.contactPoint=scylla-node-1:9042 -Pscylla.datacenter=datacenter1
```

Verify:

```bash
docker exec hopr-scylla-node-1 cqlsh -e "DESCRIBE KEYSPACE hopr"
docker exec hopr-scylla-node-1 cqlsh -e \
  "SELECT version, description, success FROM hopr.flyway_schema_history"
```

Migrations are an explicit task, not a Spring Boot startup hook: three services share this
keyspace, so migrating on boot would mean three migrators racing and a startup-time
dependency on DDL. The same task is what a CI/CD stage or a Kubernetes Job invokes.

## Constraints worth knowing before editing a migration

These were found by running the migrations against a real Scylla 6.2 cluster, not by
reading docs:

- **No semicolons in `--` comments.** Flyway's CQL parser splits statements on the
  terminator without first stripping line comments, so a semicolon inside a comment
  truncates the statement and the cluster rejects it as a syntax error.
- **Counters need tablets disabled.** Scylla 6+ defaults to tablet-based distribution,
  which does not support counter columns, so `V1` creates the keyspace with
  `tablets = {'enabled': false}`. Without it `url_click_counts` fails with
  *"Counters are not yet supported with tablets"*.
- **`V1` must stay idempotent.** Flyway's schema-history table lives inside the keyspace,
  so the keyspace has to exist before the first migration can be recorded. `ScyllaMigrator`
  replays `V1` as connection-time init SQL and Flyway then applies it again as a normal
  migration, which only works because it is `CREATE KEYSPACE IF NOT EXISTS`.
- **Flyway runs in a separate JVM.** The Flyway Gradle plugin executes inside Gradle's
  classloader, where its commons-lang3 collides with the Cassandra JDBC wrapper's
  (`IllegalAccessError` on `org.apache.commons.lang3.Strings`). `migrateScylla` is a
  `JavaExec` for that reason.
