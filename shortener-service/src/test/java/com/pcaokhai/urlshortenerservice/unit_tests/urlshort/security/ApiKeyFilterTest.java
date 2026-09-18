package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.urlshortenerservice.security.ApiKeyFilter;
import com.pcaokhai.urlshortenerservice.security.ApiKeyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFilterTest {

    @Test
    void emptyHashListFailsFastRatherThanRejectingEveryCaller() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ApiKeyFilter(List.of(), new ObjectMapper()));
        assertTrue(e.getMessage().contains("shortener.api-key.hashes"));
    }

    @Test
    void nonHexHashIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new ApiKeyFilter(List.of("not-a-digest"), new ObjectMapper()));
    }

    @Test
    void propertiesDefaultToAnEmptyListRatherThanNull() {
        ApiKeyProperties properties = new ApiKeyProperties();
        assertEquals(List.of(), properties.getHashes());
        properties.setHashes(null);
        assertEquals(List.of(), properties.getHashes());
    }
}
