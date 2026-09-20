package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.urlshortenerservice.security.ApiKeyFilter;
import com.pcaokhai.urlshortenerservice.security.ApiKeyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFilterTest {

    @Test
    void emptyOwnerMapFailsFastRatherThanRejectingEveryCaller() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ApiKeyFilter(Map.of(), new ObjectMapper()));
        assertTrue(e.getMessage().contains("shortener.api-key.owners"));
    }

    @Test
    void nonHexHashIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new ApiKeyFilter(Map.of("not-a-digest", "owner-a"), new ObjectMapper()));
    }

    @Test
    void propertiesDefaultToAnEmptyListRatherThanNull() {
        ApiKeyProperties properties = new ApiKeyProperties();
        assertEquals(List.of(), properties.getOwners());
        properties.setOwners(null);
        assertEquals(List.of(), properties.getOwners());
    }

    @Test
    void ownersParseIntoHashToOwnerPairs() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setOwners(List.of("aa11:owner-a", " bb22 : owner-b "));

        assertEquals(Map.of("aa11", "owner-a", "bb22", "owner-b"), properties.ownerByHash());
    }

    @Test
    void ownerEntryWithoutAnOwnerIdIsRejectedAtStartup() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setOwners(List.of("aa11"));

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::ownerByHash);
        assertTrue(e.getMessage().contains("owner-id"));
    }
}
