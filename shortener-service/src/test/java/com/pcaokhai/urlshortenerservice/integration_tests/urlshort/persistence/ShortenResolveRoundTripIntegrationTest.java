package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.persistence;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.application.ShortenerUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shorten-then-resolve round trip across the Scylla swap: the shortener's write path
 * (ShortenerUseCase -> DbCacheSaver -> UrlRepository.save) followed by the resolver's read
 * path (UrlRepository.findById, which is all DbLookup does), against a real Scylla node.
 *
 * This is where a wrong CQL column mapping shows up — a row can be written and read back by
 * primary key while long_url and alias silently land in the wrong (or no) columns.
 */
@Import(ShortenResolveRoundTripIntegrationTest.NoCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ShortenResolveRoundTripIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ShortenerUseCase shortenerUseCase;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void clear() {
        urlRepository.deleteAll();
    }

    @Test
    void shortenedUrlIsResolvableFromScylla() {
        // An explicit alias keeps keygen-service out of the round trip; KeyGenResolver
        // returns the alias verbatim as the short key.
        shortenerUseCase.shorten(new ShortenRequest("https://example.com/a/very/long/path", "roundtrip1"));

        UrlMapping resolved = urlRepository.findById("roundtrip1").orElseThrow();
        assertEquals("https://example.com/a/very/long/path", resolved.getLongUrl());
        assertEquals("roundtrip1", resolved.getAlias());
    }

    @TestConfiguration
    static class NoCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }
}
