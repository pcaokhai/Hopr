package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.DB;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.urlshortenerservice.urlshort.infra.DB.DbCacheSaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.EntityWriteResult;

import org.springframework.data.cassandra.core.InsertOptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DbCacheSaverTest {

    @Mock
    private CassandraOperations cassandra;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private DbCacheSaver dbCacheSaver;

    @BeforeEach
    void setUp() {
        dbCacheSaver = new DbCacheSaver(cassandra, cacheManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_whenLwtApplies_cachesAndReportsSuccess() {
        when(cacheManager.getCache("keys")).thenReturn(cache);
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(true);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertTrue(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping));
        verify(cache).put("abc123", urlMapping);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_whenLwtDoesNotApply_reportsFailureAndLeavesCacheAlone() {
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(false);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertFalse(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping));
        verifyNoInteractions(cacheManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_withTtl_insertsWithTtlOptionAndCaches() {
        when(cacheManager.getCache("keys")).thenReturn(cache);
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(true);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertTrue(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping, 60L));

        verify(cassandra).insert(eq(urlMapping), argThat((InsertOptions options) -> options.getTtl() != null
                && options.getTtl().getSeconds() == 60));
        verify(cache).put("abc123", urlMapping);
    }
}