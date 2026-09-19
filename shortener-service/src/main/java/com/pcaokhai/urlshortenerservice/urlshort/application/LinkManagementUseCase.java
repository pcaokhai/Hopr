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
package com.pcaokhai.urlshortenerservice.urlshort.application;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.model.dto.LinkListResponse;
import com.pcaokhai.common.url.model.dto.LinkResponse;
import com.pcaokhai.common.url.model.dto.UpdateLinkRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.urlshort.exception.LinkNotFoundException;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Use case backing {@code GET/PATCH/DELETE /links}: any caller holding the shared API key can
 * list, read, update, or delete any link -- see the PR description for why real per-owner
 * scoping is deliberately deferred to Phase 6 rather than half-built here.
 */
@Service
public class LinkManagementUseCase {

    private final UrlRepository urlRepository;
    private final CassandraOperations cassandra;
    private final CacheManager cacheManager;

    public LinkManagementUseCase(UrlRepository urlRepository, CassandraOperations cassandra, CacheManager cacheManager) {
        this.urlRepository = urlRepository;
        this.cassandra = cassandra;
        this.cacheManager = cacheManager;
    }

    // Unscoped full-table listing over Scylla's native paging state: there is no owner_id index
    // yet (see the PR description's concept write-up), so this is honestly "every link in the
    // system", not "my links". Fine for now only because the current auth model already lets any
    // API-key holder see any link -- Phase 6 needs a query-first table before this can narrow.
    public LinkListResponse list(int pageSize, String pageToken) {
        CassandraPageRequest pageable = pageToken == null
                ? CassandraPageRequest.first(pageSize)
                : CassandraPageRequest.of(CassandraPageRequest.first(pageSize), decodePageToken(pageToken));
        Slice<UrlMapping> slice = cassandra.slice(Query.empty().pageRequest(pageable), UrlMapping.class);
        List<LinkResponse> links = slice.getContent().stream().map(LinkResponse::from).toList();
        String nextPageToken = slice.hasNext() ? encodePageToken((CassandraPageRequest) slice.nextPageable()) : null;
        return new LinkListResponse(links, nextPageToken);
    }

    public LinkResponse get(String shortKey) {
        return LinkResponse.from(findOrThrow(shortKey));
    }

    public LinkResponse update(String shortKey, UpdateLinkRequest request) {
        UrlMapping mapping = findOrThrow(shortKey);
        if (StringUtils.hasText(request.longUrl())) {
            mapping.setLongUrl(request.longUrl());
        }
        if (request.expiresInSeconds() != null) {
            mapping.setExpiresAt(Instant.now().plusSeconds(request.expiresInSeconds()));
        }
        UrlMapping saved = urlRepository.save(mapping);
        evictCache(shortKey);
        return LinkResponse.from(saved);
    }

    public void delete(String shortKey) {
        findOrThrow(shortKey);
        urlRepository.deleteById(shortKey);
        evictCache(shortKey);
    }

    private UrlMapping findOrThrow(String shortKey) {
        return urlRepository.findById(shortKey).orElseThrow(() -> new LinkNotFoundException(shortKey));
    }

    // Only evicts this service's own cache entry (populated by DbCacheSaver on create). The
    // resolver runs its own, differently-namespaced Redis cache and can serve a stale mapping for
    // up to its 12h TTL after an update/delete -- see the PR description for why that cross-service
    // invalidation is left out of this PR.
    private void evictCache(String shortKey) {
        cacheManager.getCache("keys").evict(shortKey);
    }

    private static String encodePageToken(CassandraPageRequest pageable) {
        ByteBuffer pagingState = pageable.getPagingState();
        return pagingState == null ? null : Base64.getUrlEncoder().encodeToString(toBytes(pagingState));
    }

    private static ByteBuffer decodePageToken(String pageToken) {
        return ByteBuffer.wrap(Base64.getUrlDecoder().decode(pageToken));
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }
}
