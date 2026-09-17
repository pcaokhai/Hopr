package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.web;

import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenServiceUnvailableException;
import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SuppressWarnings("rawtypes")
public class KeyGenClientTest {

    private static final String KEYGEN_URL = "http://keygen-service:8081";

    @Mock private WebClient.Builder mockBuilder;
    @Mock private WebClient mockWebClient;

    @Mock private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private KeyGenClient keyGenClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockBuilder.build()).thenReturn(mockWebClient);
        keyGenClient = new KeyGenClient(mockBuilder, CircuitBreakerRegistry.ofDefaults(), KEYGEN_URL);
    }

    @Test
    void testGenerateKey_Success() {
        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("shortKey", "abc123");

        when(mockWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(KEYGEN_URL + "/generate")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(responseMap));

        String key = keyGenClient.generateKey();

        assertEquals("abc123", key);
    }

    @Test
    void testGenerateKey_ServiceUnavailable() {
        when(mockWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(KEYGEN_URL + "/generate")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class)).thenThrow(new RuntimeException("Service error"));

        KeygenServiceUnvailableException ex = assertThrows(
                KeygenServiceUnvailableException.class,
                () -> keyGenClient.generateKey()
        );

        assertEquals("Keygen Service Is Unavailable", ex.getMessage());
    }

    @Test
    void testGenerateKey_TimesOutInsteadOfBlockingForever() {
        when(mockWebClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(KEYGEN_URL + "/generate")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        // A keygen-service that accepts the request and then never answers.
        when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.never());

        long start = System.nanoTime();
        assertThrows(KeygenServiceUnvailableException.class, () -> keyGenClient.generateKey());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 5_000,
                "call should have been abandoned by the client timeout, took " + elapsedMillis + "ms");
    }
}
