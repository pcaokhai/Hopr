package com.pcaokhai.resolverservice.unit_tests.resolver.application;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.resolverservice.exception.KeyNotFoundException;
import com.pcaokhai.resolverservice.resolver.application.DbLookup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import com.pcaokhai.common.url.repository.UrlRepository;

import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbLookupTest {

    private UrlRepository urlRepository;
    private DbLookup dbLookup;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        dbLookup = new DbLookup(urlRepository);
    }

    @Test
    void testGetFromDb_Success() {
        String shortKey = "abc123";
        UrlMapping expected = new UrlMapping();

        when(urlRepository.findById(shortKey)).thenReturn(Optional.of(expected));

        UrlMapping result = dbLookup.getFromDb(shortKey);

        assertNotNull(result);
        assertEquals(expected, result);
        verify(urlRepository).findById(shortKey);
    }

    @Test
    void testGetFromDb_KeyNotFound() {
        String shortKey = "notfound";

        when(urlRepository.findById(shortKey)).thenReturn(Optional.empty());

        KeyNotFoundException exception = assertThrows(
                KeyNotFoundException.class,
                () -> dbLookup.getFromDb(shortKey));

        assertEquals("Key notfound Not Found", exception.getMessage());
        verify(urlRepository).findById(shortKey);
    }
}
