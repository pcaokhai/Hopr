/*
 * Copyright 2025 the original author.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.model.dto.ShortenResponse;
import com.pcaokhai.urlshortenerservice.urlshort.application.ShortenerUseCase;
import com.pcaokhai.urlshortenerservice.urlshort.infra.exceptionhandler.UrlShortenerExceptionHandler;
import com.pcaokhai.urlshortenerservice.urlshort.infra.router.ShortenerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the input validation on POST /shorten: only absolute http(s) URLs within the length
 * cap reach the use case, everything else is rejected at the API boundary with a 400.
 */
class ShortenRequestValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShortenerUseCase shortenerUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        shortenerUseCase = mock(ShortenerUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ShortenerController(shortenerUseCase))
                .setControllerAdvice(new UrlShortenerExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a?b=c", "https://example.com"})
    void acceptsAbsoluteHttpUrls(String longUrl) throws Exception {
        when(shortenerUseCase.shorten(any())).thenReturn(new ShortenResponse("http://short.ly/abc123"));

        perform(longUrl)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value("http://short.ly/abc123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file",
            "//example.com",
            "example.com",
            "https://",
            "not a url"
    })
    void rejectsAnythingThatIsNotAnAbsoluteHttpUrl(String longUrl) throws Exception {
        expectRejected(longUrl, "absolute http or https URL");
    }

    @Test
    void rejectsOverLengthUrl() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(ShortenRequest.MAX_LONG_URL_LENGTH);

        expectRejected(longUrl, "at most " + ShortenRequest.MAX_LONG_URL_LENGTH + " characters");
    }

    @Test
    void acceptsUrlExactlyAtTheLengthCap() throws Exception {
        String prefix = "https://example.com/";
        String longUrl = prefix + "a".repeat(ShortenRequest.MAX_LONG_URL_LENGTH - prefix.length());
        when(shortenerUseCase.shorten(any())).thenReturn(new ShortenResponse("http://short.ly/abc123"));

        perform(longUrl).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankUrl(String longUrl) throws Exception {
        expectRejected(longUrl, "must not be blank");
    }

    @Test
    void rejectsMissingUrl() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"abc123\"}"))
                .andExpect(status().isBadRequest());

        verify(shortenerUseCase, never()).shorten(any());
    }

    private void expectRejected(String longUrl, String expectedMessageFragment) throws Exception {
        perform(longUrl)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(expectedMessageFragment)));

        verify(shortenerUseCase, never()).shorten(any());
    }

    private org.springframework.test.web.servlet.ResultActions perform(String longUrl) throws Exception {
        return mockMvc.perform(post("/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new ShortenRequest(longUrl, "abc123"))));
    }
}
