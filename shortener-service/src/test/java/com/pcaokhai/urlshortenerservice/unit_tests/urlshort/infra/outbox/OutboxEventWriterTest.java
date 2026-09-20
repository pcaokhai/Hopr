package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.infra.outbox;

import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEvent;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {

    @Mock
    private CassandraOperations cassandra;

    private OutboxEventWriter writer;

    @BeforeEach
    void setUp() {
        writer = new OutboxEventWriter(cassandra);
    }

    @Test
    void enqueueCachePrimeEvent_writesAPendingEventForToday() {
        writer.enqueueCachePrimeEvent("abc123");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(cassandra).insert(captor.capture());

        OutboxEvent event = captor.getValue();
        assertEquals("abc123", event.getShortKey());
        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus());
        assertEquals(OutboxEvent.EVENT_TYPE_CACHE_PRIME, event.getEventType());
        assertEquals(LocalDate.now(ZoneOffset.UTC), event.getBucket());
        assertNotNull(event.getEventId());
        assertNotNull(event.getCreatedAt());
        assertNull(event.getProcessedAt());
    }
}
