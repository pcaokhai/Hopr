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
import com.pcaokhai.common.url.repository.UrlRepository;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

/** * DbCacheSaver is responsible for saving URL mappings to the database and updating the cache.
 * It uses a UrlRepository to persist the mappings and a CacheManager to manage the cache.
 *
 */
@Component
public class DbCacheSaver {

    // INSERT ... IF NOT EXISTS is a lightweight transaction: Scylla runs Paxos over the
    // replicas of the partition so the conditional is evaluated exactly once cluster-wide.
    // SERIAL is the linearizable ballot consistency that makes that guarantee hold; it costs
    // extra round trips, which is why only the custom-alias path pays for it. The generated-key
    // path stays on the plain (non-LWT) insert below, because Snowflake already makes its keys
    // unique.
    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder()
            .withIfNotExists()
            .serialConsistencyLevel(ConsistencyLevel.SERIAL)
            .build();

    private final UrlRepository urlRepository;
    private final CassandraOperations cassandra;
    private final CacheManager cacheManager;

    public DbCacheSaver(UrlRepository urlRepository, CassandraOperations cassandra, CacheManager cacheManager) {
        this.urlRepository = urlRepository;
        this.cassandra = cassandra;
        this.cacheManager = cacheManager;
    }

    public void saveUrlMapping(UrlMapping urlMapping) {
        urlRepository.save(urlMapping);
        cache(urlMapping);
    }

    /**
     * Atomically claims {@code short_key}. Returns false when the key was already taken —
     * the LWT's {@code [applied]} column came back false and nothing was written, so the
     * existing row's long_url survives.
     */
    public boolean saveUrlMappingIfAbsent(UrlMapping urlMapping) {
        boolean applied = cassandra.insert(urlMapping, IF_NOT_EXISTS).wasApplied();
        if (applied) {
            cache(urlMapping);
        }
        return applied;
    }

    private void cache(UrlMapping urlMapping) {
        cacheManager.getCache("keys").put(urlMapping.getShortKey(), urlMapping);
    }
}
