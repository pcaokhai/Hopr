package com.pcaokhai.resolverservice.integration_tests.security;

import com.pcaokhai.resolverservice.integration_tests.config.BaseIntegrationTest;
import com.pcaokhai.resolverservice.resolver.application.ResolverUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The redirect path stays public on purpose: a short link is worthless if only key holders can
 * follow it, and anyone who has the link already has the only capability the redirect grants.
 * Gating on an API key here would mean every reader of a shortened link needed one.
 *
 * <p>This pins that intent so the /v1/shorten API key filter is never copied into this service by
 * reflex -- an unauthenticated redirect is a design decision, not an oversight.
 *
 * <p>It also pins that the redirect path stays <em>unversioned</em> while the shortener's API
 * moved to /v1: an issued short link has to keep working forever, so it must not carry an API
 * version that could later be retired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicRedirectIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ResolverUseCase resolverUseCase;

    @Test
    void redirectNeedsNoApiKey() throws Exception {
        when(resolverUseCase.resolve("abc123")).thenReturn("https://example.com");
        mockMvc.perform(get("/abc123"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(redirectedUrl("https://example.com"));
    }

    @Test
    void shortLinksAreNotVersioned() throws Exception {
        // /v1 is the shortener's namespace; a short key lives at the root and always will.
        mockMvc.perform(get("/v1/abc123")).andExpect(status().isNotFound());
    }

    @Test
    void redirectIgnoresAnApiKeyIfOneIsSent() throws Exception {
        when(resolverUseCase.resolve("abc123")).thenReturn("https://example.com");
        mockMvc.perform(get("/abc123").header("X-API-Key", "whatever-nonsense"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(redirectedUrl("https://example.com"));
    }
}
