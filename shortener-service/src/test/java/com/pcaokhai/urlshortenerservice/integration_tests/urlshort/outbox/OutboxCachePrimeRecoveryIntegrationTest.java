package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.outbox;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.CachePrimePoller;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the outbox mechanism actually recovers from the exact failure this PR exists to close:
 * a process that persisted the {@code urls} row (and its outbox event) but died before the
 * cache-priming step ran. Runs against a real Scylla node (via {@link BaseIntegrationTest}) and
 * a real single-node Redis container, so the assertions exercise the same client code paths
 * production uses.
 */
@AutoConfigureMockMvc
@Import(OutboxCachePrimeRecoveryIntegrationTest.RealRedisCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class OutboxCachePrimeRecoveryIntegrationTest extends BaseIntegrationTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CachePrimePoller cachePrimePoller;

    @BeforeEach
    void clear() {
        urlRepository.deleteAll();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        deleteAllInPartition(today, OutboxEvent.STATUS_PENDING);
        deleteAllInPartition(today, OutboxEvent.STATUS_PROCESSED);
        cacheManager.getCache("keys").clear();
    }

    private void deleteAllInPartition(LocalDate bucket, String status) {
        Query query = Query.query(Criteria.where("bucket").is(bucket)).and(Criteria.where("status").is(status));
        cassandra.select(query, OutboxEvent.class).forEach(cassandra::delete);
    }

    @Test
    void pollerRecoversCachePrimingAfterACrashBetweenPersistAndPriming() {
        // Simulate the exact crash window this PR closes: the urls row and its outbox event
        // both landed durably, but the process died before anything primed the cache.
        UrlMapping mapping = new UrlMapping("crash1", "https://example.com/crashed", "crash1", null, Instant.now(), "ACTIVE");
        urlRepository.save(mapping);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OutboxEvent pending = new OutboxEvent(today, OutboxEvent.STATUS_PENDING, Instant.now(),
                Uuids.timeBased(), "crash1", OutboxEvent.EVENT_TYPE_CACHE_PRIME, null);
        cassandra.insert(pending);

        assertNull(cacheManager.getCache("keys").get("crash1"));

        cachePrimePoller.pollAndPrime();

        assertEquals(mapping.getLongUrl(),
                ((UrlMapping) cacheManager.getCache("keys").get("crash1").get()).getLongUrl());
        assertProcessed(today, "crash1");
    }

    @Test
    void happyPathShortenThenPollPrimesCacheEndToEnd() throws Exception {
        ShortenRequest request = new ShortenRequest("https://example.com/happy/path", "happy1");

        mockMvc.perform(post("/shorten").header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertNull(cacheManager.getCache("keys").get("happy1"));

        cachePrimePoller.pollAndPrime();

        assertNotNull(cacheManager.getCache("keys").get("happy1"));
        assertEquals("https://example.com/happy/path",
                ((UrlMapping) cacheManager.getCache("keys").get("happy1").get()).getLongUrl());
        assertProcessed(LocalDate.now(ZoneOffset.UTC), "happy1");
    }

    private void assertProcessed(LocalDate bucket, String shortKey) {
        Query pendingQuery = Query.query(Criteria.where("bucket").is(bucket)).and(Criteria.where("status").is(OutboxEvent.STATUS_PENDING));
        List<OutboxEvent> stillPending = cassandra.select(pendingQuery, OutboxEvent.class).stream()
                .filter(e -> e.getShortKey().equals(shortKey))
                .toList();
        assertEquals(0, stillPending.size(), "event should have moved out of PENDING");

        Query processedQuery = Query.query(Criteria.where("bucket").is(bucket)).and(Criteria.where("status").is(OutboxEvent.STATUS_PROCESSED));
        boolean processed = cassandra.select(processedQuery, OutboxEvent.class).stream()
                .anyMatch(e -> e.getShortKey().equals(shortKey));
        assertEquals(true, processed, "event should be recorded as PROCESSED");
    }

    @TestConfiguration
    static class RealRedisCacheConfig {
        @Bean
        CacheManager cacheManager() {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return RedisCacheManager.builder(factory).build();
        }
    }
}
