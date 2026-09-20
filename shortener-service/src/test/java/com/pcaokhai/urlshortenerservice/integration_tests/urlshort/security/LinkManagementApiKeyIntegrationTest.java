package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.security;

import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.application.LinkManagementUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /links management endpoints are gated on the same X-API-Key filter as /shorten (see
 * ApiKeyIntegrationTest) -- any caller holding the shared key can manage any link; there is no
 * per-caller scoping in this PR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkManagementApiKeyIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private LinkManagementUseCase linkManagementUseCase;

    @Test
    void listWithoutKeyIsRejected() throws Exception {
        mockMvc.perform(get("/v1/links"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        verify(linkManagementUseCase, never()).list(anyInt(), any(), any());
    }

    @Test
    void getWithoutKeyIsRejected() throws Exception {
        mockMvc.perform(get("/v1/links/abc123"))
                .andExpect(status().isUnauthorized());
        verify(linkManagementUseCase, never()).get(any(), any());
    }

    @Test
    void updateWithoutKeyIsRejected() throws Exception {
        mockMvc.perform(patch("/v1/links/abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized());
        verify(linkManagementUseCase, never()).update(any(), any(), any());
    }

    @Test
    void deleteWithoutKeyIsRejected() throws Exception {
        mockMvc.perform(delete("/v1/links/abc123"))
                .andExpect(status().isUnauthorized());
        verify(linkManagementUseCase, never()).delete(any(), any());
    }
}
