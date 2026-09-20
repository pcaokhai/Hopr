package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.infra.DB.LegacyOwnerBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A row written before per-owner scoping existed has a null {@code owner_id} and matches no key,
 * so it would be unmanageable forever. Against a real Scylla node: the backfill stamps it with
 * the {@code legacy} owner, leaves already-owned rows alone, and is safe to run twice.
 */
@AutoConfigureMockMvc
class LegacyOwnerBackfillIntegrationTest extends BaseIntegrationTest {

    private static final String LEGACY_KEY = "test-api-key-legacy";

    @Autowired
    private LegacyOwnerBackfill backfill;

    @Autowired
    private CqlSession session;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        urlRepository.deleteAll();
        session.execute("INSERT INTO urls (short_key, long_url) VALUES ('pre-scoping', 'https://example.com/old')");
        session.execute("INSERT INTO urls (short_key, long_url, owner_id) "
                + "VALUES ('already-owned', 'https://example.com/mine', 'owner-a')");
    }

    @Test
    void stampsNullOwnerRowsAndLeavesOwnedOnesAlone() {
        backfill.run(null);

        assertEquals("legacy", urlRepository.findById("pre-scoping").orElseThrow().getOwnerId());
        assertEquals("owner-a", urlRepository.findById("already-owned").orElseThrow().getOwnerId());
    }

    @Test
    void isSafeToRunTwice() {
        backfill.run(null);
        backfill.run(null);

        assertEquals("legacy", urlRepository.findById("pre-scoping").orElseThrow().getOwnerId());
        assertEquals("owner-a", urlRepository.findById("already-owned").orElseThrow().getOwnerId());
    }

    @Test
    void backfilledLinkBecomesManageableByTheLegacyOwnersKey() throws Exception {
        mockMvc.perform(get("/v1/links/pre-scoping").header("X-API-Key", LEGACY_KEY))
                .andExpect(status().isNotFound());

        backfill.run(null);

        mockMvc.perform(get("/v1/links/pre-scoping").header("X-API-Key", LEGACY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortKey").value("pre-scoping"));
    }
}
