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
package com.pcaokhai.urlshortenerservice.urlshort.infra.DB;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.pcaokhai.common.url.model.UrlMapping;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** * DbCacheSaver is responsible for saving URL mappings to the database and updating the cache.
 * Every write claims its short_key with a lightweight transaction, so a key maps to exactly one URL.
 *
 */
@Component
public class DbCacheSaver {

    // INSERT ... IF NOT EXISTS is a lightweight transaction: Scylla runs Paxos over the
    // replicas of the partition so the conditional is evaluated exactly once cluster-wide.
    // SERIAL is the linearizable ballot consistency that makes that guarantee hold; it costs
    // extra round trips, which every write pays for so that a short key maps to exactly one URL.
    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder()
            .withIfNotExists()
            .serialConsistencyLevel(ConsistencyLevel.SERIAL)
            .build();

    private final CassandraOperations cassandra;
    private final CacheManager cacheManager;

    public DbCacheSaver(CassandraOperations cassandra, CacheManager cacheManager) {
        this.cassandra = cassandra;
        this.cacheManager = cacheManager;
    }

    /**
     * Atomically claims {@code short_key}. Returns false when the key was already taken —
     * the LWT's {@code [applied]} column came back false and nothing was written, so the
     * existing row's long_url survives.
     */
    public boolean saveUrlMappingIfAbsent(UrlMapping urlMapping) {
        return saveUrlMappingIfAbsent(urlMapping, null);
    }

    /**
     * Same as {@link #saveUrlMappingIfAbsent(UrlMapping)}, but when {@code ttlSeconds} is given,
     * the row is written with a CQL {@code USING TTL} clause so ScyllaDB itself expires and
     * physically removes it — no application-level cleanup job is involved.
     */
    public boolean saveUrlMappingIfAbsent(UrlMapping urlMapping, Long ttlSeconds) {
        InsertOptions options = ttlSeconds == null ? IF_NOT_EXISTS : withTtl(ttlSeconds);
        boolean applied = cassandra.insert(urlMapping, options).wasApplied();
        if (applied) {
            cache(urlMapping);
        }
        return applied;
    }

    private InsertOptions withTtl(long ttlSeconds) {
        return InsertOptions.builder()
                .withIfNotExists()
                .serialConsistencyLevel(ConsistencyLevel.SERIAL)
                .ttl(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    private void cache(UrlMapping urlMapping) {
        cacheManager.getCache("keys").put(urlMapping.getShortKey(), urlMapping);
    }
}
