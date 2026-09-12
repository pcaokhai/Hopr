package com.pcaokhai.resolverservice.integration_tests.config;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // A real Scylla node rather than the generic Cassandra image: the services talk to
    // Scylla in compose and in the chart, and the CQL dialect differences that matter
    // (tablets, counters) only show up on the real thing. The init script mirrors
    // db-migration's V1/V2 at replication_factor 1, which is all a single node can serve.
    //
    // Started once for the whole JVM (the singleton-container pattern) rather than via
    // @Container: JUnit stops a @Container between test classes, but Spring caches the
    // application context — and its CqlSession — across them, so the second class would
    // talk to a dead node's port. Ryuk reaps the container when the JVM exits. Scylla is
    // also slow enough to boot that starting it per class is worth avoiding anyway.
    static final CassandraContainer SCYLLA = new CassandraContainer(
            DockerImageName.parse("scylladb/scylla:6.2").asCompatibleSubstituteFor("cassandra"))
            .withCommand("--smp 1 --memory 1G --overprovisioned 1 --developer-mode 1 --reactor-backend=epoll")
            .withInitScript("scylla-init.cql");

    static {
        SCYLLA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.redis.cluster.nodes", List::of);
        reg.add("spring.cassandra.contact-points", () -> SCYLLA.getHost() + ":" + SCYLLA.getFirstMappedPort());
        reg.add("spring.cassandra.local-datacenter", SCYLLA::getLocalDatacenter);
        reg.add("spring.cassandra.keyspace-name", () -> "hopr");
    }
}
