// Schema-as-code for the ScyllaDB keyspace: the versioned CQL migrations under
// src/main/resources/db/migration are the only place Hopr's schema is defined.
// Run them with `./gradlew :db-migration:migrateScylla`.
//
// Flyway is driven through its Java API from an isolated JavaExec rather than through the
// Flyway Gradle plugin: that plugin runs inside Gradle's own classloader, where its
// commons-lang3 collides with the one the Cassandra JDBC wrapper needs (IllegalAccessError
// on org.apache.commons.lang3.Strings). A separate JVM sidesteps it entirely.

dependencies {
    // Flyway has no native CQL support; the community plugin adds Cassandra/Scylla,
    // and it reaches the cluster over the ING JDBC wrapper around the DataStax driver.
    implementation("org.flywaydb:flyway-core:13.6.0")
    runtimeOnly("org.flywaydb:flyway-database-cassandra:13.6.0")
    runtimeOnly("com.ing.data:cassandra-jdbc-wrapper:5.0.3")
}

tasks.register<JavaExec>("migrateScylla") {
    group = "flyway"
    description = "Applies the CQL migrations to a ScyllaDB cluster."
    mainClass.set("com.pcaokhai.dbmigration.ScyllaMigrator")
    classpath = sourceSets["main"].runtimeClasspath
    // Override with -Pscylla.contactPoint=host:port when not pointing at local compose.
    systemProperty("scylla.migrations", layout.projectDirectory.dir("src/main/resources/db/migration").asFile.path)
    listOf("scylla.contactPoint", "scylla.datacenter").forEach { key ->
        (findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}
