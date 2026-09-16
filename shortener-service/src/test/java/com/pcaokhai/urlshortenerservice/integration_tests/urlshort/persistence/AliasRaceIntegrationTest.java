package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reproduces the alias-creation race the check-then-save flow allowed: two concurrent POST
 * /shorten requests for the same custom alias both passed the availability read, then both
 * wrote, and the later plain INSERT (an upsert in Scylla) silently overwrote the earlier
 * long_url — link hijacking. With INSERT ... IF NOT EXISTS, Paxos picks one winner: exactly
 * one request gets a 2xx, the other a 409, and the winner's long_url is what the table holds.
 */
@AutoConfigureMockMvc
@Import(AliasRaceIntegrationTest.NoCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class AliasRaceIntegrationTest extends BaseIntegrationTest {

    private static final String ALIAS = "racealias";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private CqlSession cqlSession;

    @BeforeEach
    void clear() {
        urlRepository.deleteAll();
    }

    @Test
    void concurrentClaimsOfTheSameAlias_leaveExactlyOneWinner() throws Exception {
        String urlA = "https://example.com/first";
        String urlB = "https://example.com/second";

        // The barrier makes both requests hit the write path at the same time, which is what
        // the old check-then-save flow needed to lose the row.
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> calls = List.of(
                    CompletableFuture.supplyAsync(() -> shorten(barrier, urlA), pool),
                    CompletableFuture.supplyAsync(() -> shorten(barrier, urlB), pool));

            List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
            assertEquals(1, statuses.stream().filter(s -> s / 100 == 2).count(), "expected exactly one 2xx, got " + statuses);
            assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "expected exactly one 409, got " + statuses);
        } finally {
            pool.shutdownNow();
        }

        Row row = cqlSession.execute(
                "SELECT short_key, long_url FROM hopr.urls WHERE short_key = '" + ALIAS + "'").one();
        String persisted = row.getString("long_url");
        // No silent overwrite: whichever request won, its long_url is the one that survived.
        assertTrue(urlA.equals(persisted) || urlB.equals(persisted), "unexpected long_url " + persisted);
        assertEquals(1, cqlSession.execute("SELECT short_key FROM hopr.urls").all().size());
    }

    private int shorten(CyclicBarrier barrier, String longUrl) {
        try {
            barrier.await();
            return mockMvc.perform(post("/shorten")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ShortenRequest(longUrl, ALIAS))))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class NoCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }
}
