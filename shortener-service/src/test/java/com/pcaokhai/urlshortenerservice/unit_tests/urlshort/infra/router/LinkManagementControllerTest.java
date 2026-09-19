package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.LinkListResponse;
import com.pcaokhai.common.url.model.dto.LinkResponse;
import com.pcaokhai.urlshortenerservice.urlshort.application.LinkManagementUseCase;
import com.pcaokhai.urlshortenerservice.urlshort.exception.LinkNotFoundException;
import com.pcaokhai.urlshortenerservice.urlshort.infra.exceptionhandler.UrlShortenerExceptionHandler;
import com.pcaokhai.urlshortenerservice.urlshort.infra.router.LinkManagementController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers GET/PATCH/DELETE /links routing, response shape, not-found mapping, and PATCH input
 * validation. Auth (X-API-Key) is covered separately by {@code LinkManagementApiKeyIntegrationTest}.
 */
class LinkManagementControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final LinkResponse SAMPLE = new LinkResponse(
            "abc123", "https://example.com", null, "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), null);

    private LinkManagementUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(LinkManagementUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LinkManagementController(useCase))
                .setControllerAdvice(new UrlShortenerExceptionHandler())
                .build();
    }

    @Test
    void listReturnsPageOfLinks() throws Exception {
        when(useCase.list(50, null)).thenReturn(new LinkListResponse(List.of(SAMPLE), null));

        mockMvc.perform(get("/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links[0].shortKey").value("abc123"))
                .andExpect(jsonPath("$.nextPageToken").doesNotExist());
    }

    @Test
    void listForwardsPageSizeAndToken() throws Exception {
        when(useCase.list(eq(10), eq("token"))).thenReturn(new LinkListResponse(List.of(), null));

        mockMvc.perform(get("/links").param("pageSize", "10").param("pageToken", "token"))
                .andExpect(status().isOk());

        verify(useCase).list(10, "token");
    }

    @Test
    void getReturnsLink() throws Exception {
        when(useCase.get("abc123")).thenReturn(SAMPLE);

        mockMvc.perform(get("/links/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getReturnsNotFoundForUnknownShortKey() throws Exception {
        when(useCase.get("missing")).thenThrow(new LinkNotFoundException("missing"));

        mockMvc.perform(get("/links/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("missing")));
    }

    @Test
    void updateAppliesLongUrlChange() throws Exception {
        LinkResponse updated = new LinkResponse("abc123", "https://updated.example.com", null, "ACTIVE",
                Instant.parse("2026-01-01T00:00:00Z"), null);
        when(useCase.update(eq("abc123"), any())).thenReturn(updated);

        mockMvc.perform(patch("/links/abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://updated.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://updated.example.com"));
    }

    @Test
    void updateReturnsNotFoundForUnknownShortKey() throws Exception {
        when(useCase.update(eq("missing"), any())).thenThrow(new LinkNotFoundException("missing"));

        mockMvc.perform(patch("/links/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://updated.example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRejectsInvalidLongUrl() throws Exception {
        mockMvc.perform(patch("/links/abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("absolute http or https URL")));

        verify(useCase, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void updateRejectsNonPositiveExpiresInSeconds() throws Exception {
        mockMvc.perform(patch("/links/abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInSeconds\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expiresInSeconds must be a positive number of seconds")));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/links/abc123")).andExpect(status().isNoContent());

        verify(useCase).delete("abc123");
    }

    @Test
    void deleteReturnsNotFoundForUnknownShortKey() throws Exception {
        org.mockito.Mockito.doThrow(new LinkNotFoundException("missing")).when(useCase).delete("missing");

        mockMvc.perform(delete("/links/missing")).andExpect(status().isNotFound());
    }
}
