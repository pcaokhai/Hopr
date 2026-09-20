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
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Use case backing {@code GET/PATCH/DELETE /v1/links}, scoped to the owner the caller's API key
 * maps to: a caller only ever sees, changes, or deletes links its own key created.
 *
 * <p>A link owned by somebody else is reported as {@code 404}, not {@code 403} -- a 403 would
 * confirm that the short key exists, which is exactly the fact the other owner's scoping is
 * meant to hide.
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

    /**
     * One page of the caller's links.
     *
     * <p>{@code pageSize} bounds the rows Scylla <em>scans</em>, not the rows that match the
     * owner filter, so a page can come back with an empty {@code links} array and a non-null
     * {@code nextPageToken}. A caller must follow the token until it is null rather than stop at
     * the first empty page.
     */
    // ponytail: owner scoping via ALLOW FILTERING over the same full-table scan, because
    // owner_id is not part of any key -- Scylla reads every partition and discards the rows that
    // do not match, so this is correct but scales with the whole table, not with one owner's
    // links. Upgrade path when that stops being cheap: a query-first `urls_by_owner` table keyed
    // on (owner_id, short_key), written alongside `urls` and read here instead.
    public LinkListResponse list(int pageSize, String pageToken, String ownerId) {
        CassandraPageRequest pageable = pageToken == null
                ? CassandraPageRequest.first(pageSize)
                : CassandraPageRequest.of(CassandraPageRequest.first(pageSize), decodePageToken(pageToken));
        Query query = Query.query(Criteria.where("owner_id").is(ownerId))
                .withAllowFiltering()
                .pageRequest(pageable);
        Slice<UrlMapping> slice = cassandra.slice(query, UrlMapping.class);
        List<LinkResponse> links = slice.getContent().stream().map(LinkResponse::from).toList();
        String nextPageToken = slice.hasNext() ? encodePageToken((CassandraPageRequest) slice.nextPageable()) : null;
        return new LinkListResponse(links, nextPageToken);
    }

    public LinkResponse get(String shortKey, String ownerId) {
        return LinkResponse.from(findOwnedOrThrow(shortKey, ownerId));
    }

    public LinkResponse update(String shortKey, UpdateLinkRequest request, String ownerId) {
        UrlMapping mapping = findOwnedOrThrow(shortKey, ownerId);
        if (StringUtils.hasText(request.longUrl())) {
            mapping.setLongUrl(request.longUrl());
        }
        if (request.expiresInSeconds() != null) {
            mapping.setExpiresAt(Instant.now().plusSeconds(request.expiresInSeconds()));
        }
        UrlMapping saved = saveWithTtl(mapping);
        evictCache(shortKey);
        return LinkResponse.from(saved);
    }

    // Plain repository.save() is a TTL-less INSERT (see UrlRepository's javadoc), which would
    // silently strip the `USING TTL` clause DbCacheSaver wrote at creation time -- making an
    // expiring link permanent, or leaving a newly-set expiresInSeconds never actually applied by
    // ScyllaDB. Re-derive the remaining TTL from expiresAt and write through it the same way.
    private UrlMapping saveWithTtl(UrlMapping mapping) {
        Instant expiresAt = mapping.getExpiresAt();
        if (expiresAt == null) {
            return cassandra.insert(mapping);
        }
        long ttlSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
        InsertOptions options = InsertOptions.builder().ttl(Duration.ofSeconds(ttlSeconds)).build();
        return cassandra.insert(mapping, options).getEntity();
    }

    public void delete(String shortKey, String ownerId) {
        findOwnedOrThrow(shortKey, ownerId);
        urlRepository.deleteById(shortKey);
        evictCache(shortKey);
    }

    // "Not yours" and "does not exist" deliberately produce the same 404: a distinct 403 would
    // tell one owner which short keys another owner has claimed.
    private UrlMapping findOwnedOrThrow(String shortKey, String ownerId) {
        return urlRepository.findById(shortKey)
                .filter(mapping -> ownerId.equals(mapping.getOwnerId()))
                .orElseThrow(() -> new LinkNotFoundException(shortKey));
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
