package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.persistence;

import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves ScyllaDB's native per-row TTL — not application code — is what removes an expired
 * short link. A row written with {@code expiresInSeconds} disappears from {@link UrlRepository}
 * on its own once the TTL elapses, against a real Scylla node (see {@link BaseIntegrationTest}),
 * and a row written without one survives well past that window.
 */
@AutoConfigureMockMvc
@Import(UrlExpiryIntegrationTest.NoCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class UrlExpiryIntegrationTest extends BaseIntegrationTest {

    private static final int TTL_SECONDS = 2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void clear() {
        urlRepository.deleteAll();
    }

    @Test
    void linkWithTtlBecomesUnresolvableAfterItExpires() throws Exception {
        ShortenRequest request = new ShortenRequest(
                "https://example.com/expires-soon", "ttlkey1", (long) TTL_SECONDS);

        mockMvc.perform(post("/shorten").header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertTrue(urlRepository.findById("ttlkey1").isPresent());

        // Scylla removes TTL'd rows on read/compaction; poll instead of a bare sleep so the
        // assertion tolerates normal scheduling jitter around the TTL boundary.
        assertTrue(pollUntilAbsent("ttlkey1", Duration.ofSeconds(TTL_SECONDS + 8)));
    }

    private boolean pollUntilAbsent(String shortKey, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (urlRepository.findById(shortKey).isEmpty()) {
                return true;
            }
            Thread.sleep(500);
        }
        return urlRepository.findById(shortKey).isEmpty();
    }

    @Test
    void linkWithoutTtlRemainsResolvable() throws Exception {
        ShortenRequest request = new ShortenRequest("https://example.com/never-expires", "nottlkey1");

        mockMvc.perform(post("/shorten").header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // No TTL was requested, so the row must still be there well after the TTL window
        // used above would have expired it.
        Thread.sleep(Duration.ofSeconds(TTL_SECONDS + 1).toMillis());
        assertTrue(urlRepository.findById("nottlkey1").isPresent());
    }

    @TestConfiguration
    static class NoCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }
}
