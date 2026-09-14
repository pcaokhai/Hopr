package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.pcaokhai.common.url.model.UrlMapping;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shorten-then-resolve round trip across the Scylla swap: a real POST /shorten through the
 * controller and the write path (ShortenerUseCase -> DbCacheSaver -> UrlRepository.save), then
 * the resolver's read path (UrlRepository.findById, which is all DbLookup does), plus a raw CQL
 * read of the persisted row, against a real Scylla node.
 *
 * This is where a wrong CQL column mapping shows up — a row can be written and read back by
 * primary key while long_url and alias silently land in the wrong (or no) columns.
 */
@AutoConfigureMockMvc
@Import(ShortenResolveRoundTripIntegrationTest.NoCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ShortenResolveRoundTripIntegrationTest extends BaseIntegrationTest {

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
    void shortenedUrlIsResolvableFromScylla() throws Exception {
        // An explicit alias keeps keygen-service out of the round trip; KeyGenResolver
        // returns the alias verbatim as the short key.
        ShortenRequest request = new ShortenRequest("https://example.com/a/very/long/path", "roundtrip1");

        String body = mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UrlMapping resolved = urlRepository.findById("roundtrip1").orElseThrow();
        assertEquals("https://example.com/a/very/long/path", resolved.getLongUrl());
        assertEquals("roundtrip1", resolved.getAlias());

        // Raw CQL, bypassing the entity mapper: proves the values really landed in the
        // snake_case columns the Flyway migration defines.
        Row row = cqlSession.execute(
                "SELECT short_key, long_url, alias FROM hopr.urls WHERE short_key = 'roundtrip1'").one();
        assertEquals("https://example.com/a/very/long/path", row.getString("long_url"));
        assertEquals("roundtrip1", row.getString("alias"));

        System.out.println("EVIDENCE POST /shorten -> 200 " + body);
        System.out.println("EVIDENCE cqlsh> SELECT short_key, long_url, alias FROM hopr.urls;");
        System.out.println("EVIDENCE  " + row.getFormattedContents());
    }

    @TestConfiguration
    static class NoCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }
}
