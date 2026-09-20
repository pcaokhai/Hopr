package com.pcaokhai.clickanalyticsservice.integration_tests;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Copied from resolver-service's {@code BaseIntegrationTest} -- see that class for why a real
 * Scylla container started once for the whole JVM, with the real db-migration Flyway files
 * applied at replication_factor 1, is used instead of a generic Cassandra image or an
 * embedded fake.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    static final CassandraContainer SCYLLA = new CassandraContainer(
            DockerImageName.parse("scylladb/scylla:6.2").asCompatibleSubstituteFor("cassandra"))
            .withCommand("--smp 1 --memory 1G --overprovisioned 1 --developer-mode 1 --reactor-backend=epoll");

    static final Path MIGRATIONS = Path.of("..", "db-migration", "src", "main", "resources", "db", "migration");

    static {
        SCYLLA.start();
        applyMigrations();
    }

    static void applyMigrations() {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(SCYLLA.getHost(), SCYLLA.getFirstMappedPort()))
                .withLocalDatacenter(SCYLLA.getLocalDatacenter())
                .build();
             Stream<Path> files = Files.list(MIGRATIONS)) {
            files.sorted(Comparator.comparingInt(BaseIntegrationTest::version))
                    .flatMap(BaseIntegrationTest::statements)
                    .map(cql -> cql.replace("'replication_factor': 3", "'replication_factor': 1"))
                    .forEach(session::execute);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int version(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    static Stream<String> statements(Path file) {
        try {
            String body = Files.readAllLines(file).stream()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            return Stream.of(body.split(";")).map(String::strip).filter(s -> !s.isEmpty());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.cassandra.contact-points", () -> SCYLLA.getHost() + ":" + SCYLLA.getFirstMappedPort());
        reg.add("spring.cassandra.local-datacenter", SCYLLA::getLocalDatacenter);
        reg.add("spring.cassandra.keyspace-name", () -> "hopr");
    }
}
