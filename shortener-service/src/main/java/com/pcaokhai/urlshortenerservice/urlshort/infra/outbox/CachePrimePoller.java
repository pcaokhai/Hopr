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
 * <p>This is also the extensibility point Phase 5's next PR (publishing {@code UrlCreated}
 * events to Kafka) is expected to plug into: an additional consumer reading the same PENDING
 * events and, once it has published, moving its own copy of the bookkeeping to PROCESSED --
 * without this class or the outbox table needing to know Kafka exists.
 */
@Component
public class CachePrimePoller {

    private static final String CACHE_NAME = "keys";

    private final CassandraOperations cassandra;
    private final UrlRepository urlRepository;
    private final CacheManager cacheManager;

    public CachePrimePoller(CassandraOperations cassandra, UrlRepository urlRepository, CacheManager cacheManager) {
        this.cassandra = cassandra;
        this.urlRepository = urlRepository;
        this.cacheManager = cacheManager;
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
        urlRepository.findById(event.getShortKey())
                .filter(mapping -> mapping.getExpiresAt() == null)
                .ifPresent(this::cache);
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
