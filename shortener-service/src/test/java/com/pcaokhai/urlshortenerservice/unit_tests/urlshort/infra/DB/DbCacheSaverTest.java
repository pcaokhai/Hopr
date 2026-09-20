package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.DB;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.urlshortenerservice.urlshort.infra.DB.DbCacheSaver;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private OutboxEventWriter outboxEventWriter;

    private DbCacheSaver dbCacheSaver;

    @BeforeEach
    void setUp() {
        dbCacheSaver = new DbCacheSaver(cassandra, outboxEventWriter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_whenLwtApplies_enqueuesCachePrimeEventAndReportsSuccess() {
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(true);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertTrue(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping));
        verify(outboxEventWriter).enqueueCachePrimeEvent("abc123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_whenLwtDoesNotApply_reportsFailureAndLeavesOutboxAlone() {
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(false);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertFalse(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping));
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveIfAbsent_withTtl_insertsWithTtlOptionAndSkipsOutbox() {
        UrlMapping urlMapping = new UrlMapping("abc123", "https://example.com", "abc123");
        EntityWriteResult<UrlMapping> result = mock(EntityWriteResult.class);
        when(result.wasApplied()).thenReturn(true);
        when(cassandra.insert(eq(urlMapping), any(InsertOptions.class))).thenReturn(result);

        assertTrue(dbCacheSaver.saveUrlMappingIfAbsent(urlMapping, 60L));

        verify(cassandra).insert(eq(urlMapping), argThat((InsertOptions options) -> options.getTtl() != null
                && options.getTtl().getSeconds() == 60));
        verifyNoInteractions(outboxEventWriter);
    }
}
