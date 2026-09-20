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
package com.pcaokhai.urlshortenerservice.urlshort.infra.outbox;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.CassandraType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A durable record of "this happened" for shortener-service's create flow, written in the
 * outbox table described in db-migration's V5 migration. See that file for why the day
 * bucket and status are both part of the partition key.
 */
@Table("outbox_events")
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String EVENT_TYPE_CACHE_PRIME = "CACHE_PRIME";

    @PrimaryKeyColumn(name = "bucket", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private LocalDate bucket;

    @PrimaryKeyColumn(name = "status", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private String status;

    @PrimaryKeyColumn(name = "created_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "event_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
    @CassandraType(type = CassandraType.Name.TIMEUUID)
    private UUID eventId;

    @Column("short_key")
    private String shortKey;

    @Column("event_type")
    private String eventType;

    @Column("processed_at")
    private Instant processedAt;

    public OutboxEvent() {}

    public OutboxEvent(LocalDate bucket, String status, Instant createdAt, UUID eventId,
                        String shortKey, String eventType, Instant processedAt) {
        this.bucket = bucket;
        this.status = status;
        this.createdAt = createdAt;
        this.eventId = eventId;
        this.shortKey = shortKey;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public LocalDate getBucket() { return bucket; }

    public String getStatus() { return status; }

    public Instant getCreatedAt() { return createdAt; }

    public UUID getEventId() { return eventId; }

    public String getShortKey() { return shortKey; }

    public String getEventType() { return eventType; }

    public Instant getProcessedAt() { return processedAt; }

    /** Same identity, moved into the PROCESSED partition with a processed timestamp set. */
    public OutboxEvent asProcessed(Instant processedAt) {
        return new OutboxEvent(bucket, STATUS_PROCESSED, createdAt, eventId, shortKey, eventType, processedAt);
    }
}
