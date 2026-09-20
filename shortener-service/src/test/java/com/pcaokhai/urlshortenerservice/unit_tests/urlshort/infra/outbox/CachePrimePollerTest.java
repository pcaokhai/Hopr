package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.outbox;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.CachePrimePoller;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraBatchOperations;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachePrimePollerTest {

    @Mock
    private CassandraOperations cassandra;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Mock
    private CassandraBatchOperations batchOperations;

    private CachePrimePoller poller;

    @BeforeEach
    void setUp() {
        poller = new CachePrimePoller(cassandra, urlRepository, cacheManager);
    }

    @Test
    void processBucket_primesCacheAndMarksEventProcessed() {
        LocalDate bucket = LocalDate.now();
        OutboxEvent pending = new OutboxEvent(bucket, OutboxEvent.STATUS_PENDING, Instant.now(),
                UUID.randomUUID(), "abc123", OutboxEvent.EVENT_TYPE_CACHE_PRIME, null);
        when(cassandra.select(any(Query.class), eq(OutboxEvent.class))).thenReturn(List.of(pending));

        UrlMapping mapping = new UrlMapping("abc123", "https://example.com", "abc123");
        when(urlRepository.findById("abc123")).thenReturn(Optional.of(mapping));
        when(cacheManager.getCache("keys")).thenReturn(cache);
        when(cassandra.batchOps()).thenReturn(batchOperations);
        when(batchOperations.delete(any(OutboxEvent.class))).thenReturn(batchOperations);
        when(batchOperations.insert(any(OutboxEvent.class))).thenReturn(batchOperations);

        poller.processBucket(bucket);

        verify(cache).put("abc123", mapping);
        verify(batchOperations).delete(pending);
        verify(batchOperations).insert(argThat((OutboxEvent e) -> e.getStatus().equals(OutboxEvent.STATUS_PROCESSED)
                && e.getEventId().equals(pending.getEventId())));
        verify(batchOperations).execute();
    }

    @Test
    void processBucket_whenUrlRowIsGone_stillMarksEventProcessedWithoutCaching() {
        LocalDate bucket = LocalDate.now();
        OutboxEvent pending = new OutboxEvent(bucket, OutboxEvent.STATUS_PENDING, Instant.now(),
                UUID.randomUUID(), "gone", OutboxEvent.EVENT_TYPE_CACHE_PRIME, null);
        when(cassandra.select(any(Query.class), eq(OutboxEvent.class))).thenReturn(List.of(pending));
        when(urlRepository.findById("gone")).thenReturn(Optional.empty());
        when(cassandra.batchOps()).thenReturn(batchOperations);
        when(batchOperations.delete(any(OutboxEvent.class))).thenReturn(batchOperations);
        when(batchOperations.insert(any(OutboxEvent.class))).thenReturn(batchOperations);

        poller.processBucket(bucket);

        verifyNoInteractions(cacheManager);
        verify(batchOperations).execute();
    }

    @Test
    void processBucket_withNoPendingEvents_doesNothing() {
        when(cassandra.select(any(Query.class), eq(OutboxEvent.class))).thenReturn(List.of());

        poller.processBucket(LocalDate.now());

        verifyNoInteractions(urlRepository, cacheManager);
        verify(cassandra, never()).batchOps();
    }
}
