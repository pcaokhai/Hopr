package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.model.dto.UpdateLinkRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two API keys mapped to two owners (see application-test.yml), each creating a link against a
 * real Scylla node: neither owner may see or touch the other's link through the management
 * endpoints, while both keys still work on {@code POST /v1/shorten}.
 *
 * <p>The "not yours" responses are asserted as 404, not 403: a 403 would confirm to one owner
 * that the other owner holds that short key.
 */
@AutoConfigureMockMvc
class LinkOwnerScopingIntegrationTest extends BaseIntegrationTest {

    private static final String KEY_A = "test-api-key";
    private static final String KEY_B = "test-api-key-2";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void seed() throws Exception {
        urlRepository.deleteAll();
        shorten(KEY_A, "owned-by-a");
        shorten(KEY_B, "owned-by-b");
    }

    @Test
    void bothKeysCanShortenAndEachOwnsWhatItCreated() {
        assertEquals("owner-a", urlRepository.findById("owned-by-a").orElseThrow().getOwnerId());
        assertEquals("owner-b", urlRepository.findById("owned-by-b").orElseThrow().getOwnerId());
    }

    @Test
    void listReturnsOnlyTheCallersOwnLinks() throws Exception {
        String body = mockMvc.perform(get("/v1/links").header("X-API-Key", KEY_A))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("owned-by-a"), body);
        assertTrue(!body.contains("owned-by-b"), "owner A's listing leaked owner B's link: " + body);
    }

    @Test
    void anotherOwnersLinkIsNotFoundRatherThanForbidden() throws Exception {
        mockMvc.perform(get("/v1/links/owned-by-b").header("X-API-Key", KEY_A))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/v1/links/owned-by-b").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateLinkRequest("https://hijacked.example.com", null))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/v1/links/owned-by-b").header("X-API-Key", KEY_A))
                .andExpect(status().isNotFound());

        // Untouched: the rejected PATCH and DELETE were rejected before they reached the row.
        assertEquals("https://example.com/owned-by-b",
                urlRepository.findById("owned-by-b").orElseThrow().getLongUrl());
    }

    @Test
    void eachOwnerCanStillManageItsOwnLink() throws Exception {
        mockMvc.perform(get("/v1/links/owned-by-b").header("X-API-Key", KEY_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortKey").value("owned-by-b"));

        mockMvc.perform(patch("/v1/links/owned-by-a").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateLinkRequest("https://updated.example.com", null))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/v1/links/owned-by-a").header("X-API-Key", KEY_A))
                .andExpect(status().isNoContent());
    }

    @Test
    void theUnversionedPathsAreGoneRatherThanSilentlyUnscoped() throws Exception {
        // The old routes were removed outright (no redirect, no alias): this project has one
        // known client, the frontend in this repo, so there is nothing to keep compatible.
        mockMvc.perform(post("/shorten").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShortenRequest("https://example.com", "unversioned"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/links").header("X-API-Key", KEY_A))
                .andExpect(status().isNotFound());
    }

    private void shorten(String apiKey, String alias) throws Exception {
        mockMvc.perform(post("/v1/shorten").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShortenRequest("https://example.com/" + alias, alias))))
                .andExpect(status().isOk());
    }
}
