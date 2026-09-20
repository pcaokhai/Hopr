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

import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Writes the outbox record that stands in for "the cache still needs priming for this
 * short key". Called right after {@code DbCacheSaver}'s LWT insert applies.
 *
 * <p>ScyllaDB (like Cassandra) refuses a batch that mixes a conditional (LWT) statement with
 * writes to another table, so this insert cannot be made part of the same atomic operation as
 * the {@code urls} row's LWT insert -- it is a second, unconditional write issued immediately
 * after that insert applies. See the PR description for the consistency guarantee this
 * actually achieves versus what full atomicity would mean.
 */
@Component
public class OutboxEventWriter {

    private final CassandraOperations cassandra;

    public OutboxEventWriter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    public void enqueueCachePrimeEvent(String shortKey) {
        Instant now = Instant.now();
        OutboxEvent event = new OutboxEvent(
                LocalDate.now(ZoneOffset.UTC),
                OutboxEvent.STATUS_PENDING,
                now,
                Uuids.timeBased(),
                shortKey,
                OutboxEvent.EVENT_TYPE_CACHE_PRIME,
                null);
        cassandra.insert(event);
    }
}
