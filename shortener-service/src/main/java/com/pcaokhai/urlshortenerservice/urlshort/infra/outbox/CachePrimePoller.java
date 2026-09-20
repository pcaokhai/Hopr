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

import com.pcaokhai.common.event.UrlCreatedEvent;
import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The only consumer of the outbox table today: reads still-PENDING events and primes the
 * Redis cache from the {@code urls} row each one points at, then moves the event to
 * PROCESSED. A crash between {@code DbCacheSaver}'s persist and this step no longer loses
 * the cache-priming step silently -- the next poll picks the event back up.
 *
 * <p>Also publishes {@link UrlCreatedEvent} to Kafka for each event it processes (via
 * {@link UrlCreatedEventPublisher}), rather than running a second poll-and-mark-processed loop
 * over the same PENDING partitions -- the table's single {@code status} column can't cleanly
 * track two independent consumers' progress at once.
 */
@Component
public class CachePrimePoller {

    private static final String CACHE_NAME = "keys";

    private final CassandraOperations cassandra;
    private final UrlRepository urlRepository;
    private final CacheManager cacheManager;
    private final UrlCreatedEventPublisher urlCreatedEventPublisher;

    public CachePrimePoller(
            CassandraOperations cassandra,
            UrlRepository urlRepository,
            CacheManager cacheManager,
            UrlCreatedEventPublisher urlCreatedEventPublisher) {
        this.cassandra = cassandra;
        this.urlRepository = urlRepository;
        this.cacheManager = cacheManager;
        this.urlCreatedEventPublisher = urlCreatedEventPublisher;
    }

    /**
     * Scans today's and yesterday's PENDING partitions -- yesterday too, so an event written
     * just before UTC midnight isn't missed by a poll that only ever looks at "today".
     */
    @Scheduled(fixedDelayString = "${shortener.outbox.poll-interval-ms:2000}")
    public void pollAndPrime() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        processBucket(today);
        processBucket(today.minusDays(1));
    }

    public void processBucket(LocalDate bucket) {
        Query query = Query.query(Criteria.where("bucket").is(bucket))
                .and(Criteria.where("status").is(OutboxEvent.STATUS_PENDING));
        List<OutboxEvent> pending = cassandra.select(query, OutboxEvent.class);
        for (OutboxEvent event : pending) {
            prime(event);
        }
    }

    private void prime(OutboxEvent event) {
        urlRepository.findById(event.getShortKey()).ifPresent(mapping -> {
            if (mapping.getExpiresAt() == null) {
                cache(mapping);
            }
            urlCreatedEventPublisher.publish(
                    UrlCreatedEvent.of(mapping.getShortKey(), mapping.getLongUrl(), event.getCreatedAt()));
        });
        markProcessed(event);
    }

    private void cache(UrlMapping mapping) {
        cacheManager.getCache(CACHE_NAME).put(mapping.getShortKey(), mapping);
    }

    private void markProcessed(OutboxEvent event) {
        OutboxEvent processed = event.asProcessed(Instant.now());
        cassandra.batchOps()
                .delete(event)
                .insert(processed)
                .execute();
    }
}
