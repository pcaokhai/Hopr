package com.pcaokhai.dbmigration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the versioned CQL migrations in {@code src/main/resources/db/migration} to a
 * ScyllaDB cluster.
 *
 * <p>Run via {@code ./gradlew :db-migration:migrateScylla}. Migrations are a deliberate,
 * separately invoked step rather than something that happens on application startup:
 * three services share one keyspace, so booting them would mean three migrators racing
 * each other and a startup-time dependency on DDL.
 *
 * <p>Chicken-and-egg: Flyway's own schema-history table lives inside the keyspace, so the
 * keyspace must exist before the first migration can be recorded. The connection therefore
 * targets the always-present {@code system} keyspace and replays
 * {@code V1__create_keyspace.cql} — which is idempotent — as connection-time init SQL.
 * V1 then runs again as a normal migration and is recorded, so that file remains the single
 * source of truth for the keyspace definition.
 */
public final class ScyllaMigrator {

    private static final String KEYSPACE = "hopr";

    private ScyllaMigrator() {}

    public static void main(String[] args) throws Exception {
        String contactPoint = System.getProperty("scylla.contactPoint", "127.0.0.1:9042");
        String datacenter = System.getProperty("scylla.datacenter", "datacenter1");
        Path migrations = Path.of(System.getProperty("scylla.migrations", "src/main/resources/db/migration"));

        MigrateResult result = migrate(contactPoint, datacenter, migrations);
        System.out.printf(
                "Applied %d migration(s) to keyspace '%s' at %s; schema version is now %s%n",
                result.migrationsExecuted, KEYSPACE, contactPoint, result.targetSchemaVersion);
    }

    /** Visible for tests: runs the migrations and returns Flyway's report. */
    public static MigrateResult migrate(String contactPoint, String datacenter, Path migrations)
            throws Exception {
        return Flyway.configure()
                .dataSource(jdbcUrl(contactPoint, datacenter), null, null)
                .defaultSchema(KEYSPACE)
                .locations("filesystem:" + migrations.toAbsolutePath())
                .sqlMigrationSuffixes(".cql")
                .initSql(keyspaceBootstrap(migrations))
                .load()
                .migrate();
    }

    private static String jdbcUrl(String contactPoint, String datacenter) {
        // QUORUM: with replication_factor 3 a write must reach 2 of 3 replicas, so the
        // schema is durable even if one node is lost mid-migration.
        return "jdbc:cassandra://%s/system?localdatacenter=%s&consistencylevel=QUORUM"
                .formatted(contactPoint, datacenter);
    }

    private static String keyspaceBootstrap(Path migrations) throws Exception {
        try (Stream<String> lines = Files.lines(migrations.resolve("V1__create_keyspace.cql"))) {
            return lines.filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + " " + b)
                    .trim();
        }
    }
}
