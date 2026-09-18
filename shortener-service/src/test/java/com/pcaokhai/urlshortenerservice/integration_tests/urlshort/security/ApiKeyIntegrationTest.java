package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.model.dto.ShortenResponse;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.application.ShortenerUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /shorten write path is gated on a valid X-API-Key; the platform's actuator endpoints are not.
 * The accepted key for the test profile is `test-api-key`, configured as its SHA-256 digest in
 * application-test.yml -- the test never has access to a plaintext key from configuration, which is
 * the point of storing digests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_KEY = "test-api-key";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ShortenerUseCase shortenerUseCase;

    private String body() throws Exception {
        return objectMapper.writeValueAsString(new ShortenRequest("https://example.com", null));
    }

    @Test
    void validKeyIsAccepted() throws Exception {
        when(shortenerUseCase.shorten(any())).thenReturn(new ShortenResponse("https://short.url/abc123"));
        mockMvc.perform(post("/shorten")
                        .header("X-API-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").exists());
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Missing or invalid X-API-Key header"));
        // Rejected at the boundary: no keygen call, no row written.
        verify(shortenerUseCase, never()).shorten(any());
    }

    @Test
    void invalidKeyIsRejected() throws Exception {
        mockMvc.perform(post("/shorten")
                        .header("X-API-Key", "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        verify(shortenerUseCase, never()).shorten(any());
    }

    @Test
    void actuatorStaysUnauthenticated() throws Exception {
        // Not asserting 200: health reports DOWN in this test (no Redis cluster). What matters is
        // that the filter is not in the way -- the platform's probes and scrapes need no API key.
        mockMvc.perform(get("/actuator/health")).andExpect(status().is(not(401)));
    }
}
