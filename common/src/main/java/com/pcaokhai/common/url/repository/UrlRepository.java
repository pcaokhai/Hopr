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
package com.pcaokhai.common.url.repository;

import com.pcaokhai.common.url.model.UrlMapping;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing URL mappings in ScyllaDB (CQL).
 * This interface extends CassandraRepository to provide CRUD operations for UrlMapping entities.
 *
 * <p>{@code save} maps to a plain CQL {@code INSERT}, which in Cassandra/Scylla is an
 * upsert: writing an existing partition key overwrites the row rather than failing. That is
 * safe only for the generated-key path, whose keys are already unique. Writes that must claim
 * a user-chosen key go through the lightweight transaction in the shortener's {@code
 * DbCacheSaver.saveUrlMappingIfAbsent} instead.
 */
@Repository
public interface UrlRepository extends CassandraRepository<UrlMapping, String> {
}
