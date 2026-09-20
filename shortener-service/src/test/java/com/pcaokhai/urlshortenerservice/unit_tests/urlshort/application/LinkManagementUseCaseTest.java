package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.application;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.model.dto.LinkListResponse;
import com.pcaokhai.common.url.model.dto.LinkResponse;
import com.pcaokhai.common.url.model.dto.UpdateLinkRequest;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.urlshort.application.LinkManagementUseCase;
import com.pcaokhai.urlshortenerservice.urlshort.exception.LinkNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.EntityWriteResult;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkManagementUseCaseTest {

    @Mock private UrlRepository urlRepository;
    @Mock private CassandraOperations cassandra;
    @Mock private CacheManager cacheManager;

    private LinkManagementUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LinkManagementUseCase(urlRepository, cassandra, cacheManager);
    }

    private UrlMapping mapping() {
        return mapping("owner-a");
    }

    private UrlMapping mapping(String ownerId) {
        UrlMapping mapping = new UrlMapping("abc123", "https://example.com", null,
                null, Instant.parse("2026-01-01T00:00:00Z"), "ACTIVE");
        mapping.setOwnerId(ownerId);
        return mapping;
    }

    @Test
    void get_throwsNotFound_whenTheLinkBelongsToAnotherOwner() {
        when(urlRepository.findById("abc123")).thenReturn(java.util.Optional.of(mapping("owner-b")));

        assertThrows(LinkNotFoundException.class, () -> useCase.get("abc123", "owner-a"));
    }

    @Test
    void list_returnsPageWithNoNextTokenWhenLastPage() {
        Slice<UrlMapping> slice = new SliceImpl<>(List.of(mapping()), PageRequest.of(0, 50), false);
        when(cassandra.slice(any(Query.class), org.mockito.ArgumentMatchers.eq(UrlMapping.class))).thenReturn(slice);

        LinkListResponse result = useCase.list(50, null, "owner-a");

        assertEquals(1, result.links().size());
        assertEquals("abc123", result.links().get(0).shortKey());
        assertNull(result.nextPageToken());
    }

    @Test
    void get_returnsLink_whenPresent() {
        when(urlRepository.findById("abc123")).thenReturn(Optional.of(mapping()));

        LinkResponse response = useCase.get("abc123", "owner-a");

        assertEquals("https://example.com", response.longUrl());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void get_throwsNotFound_whenAbsent() {
        when(urlRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(LinkNotFoundException.class, () -> useCase.get("missing", "owner-a"));
    }

    @Test
    void update_changesLongUrlAndEvictsCache() {
        when(urlRepository.findById("abc123")).thenReturn(Optional.of(mapping()));
        when(cassandra.insert(any(UrlMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("keys")).thenReturn(cache);

        LinkResponse response = useCase.update("abc123", new UpdateLinkRequest("https://updated.example.com", null), "owner-a");

        assertEquals("https://updated.example.com", response.longUrl());
        verify(cache).evict("abc123");
    }

    @Test
    void update_withExpiresInSeconds_savesThroughTtlAwarePath() {
        when(urlRepository.findById("abc123")).thenReturn(Optional.of(mapping()));
        @SuppressWarnings("unchecked")
        EntityWriteResult<UrlMapping> writeResult = mock(EntityWriteResult.class);
        when(cassandra.insert(any(UrlMapping.class), any(InsertOptions.class))).thenAnswer(invocation -> {
            when(writeResult.getEntity()).thenReturn(invocation.getArgument(0));
            return writeResult;
        });
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("keys")).thenReturn(cache);

        useCase.update("abc123", new UpdateLinkRequest(null, 3600L), "owner-a");

        verify(cassandra).insert(any(UrlMapping.class), any(InsertOptions.class));
    }

    @Test
    void update_throwsNotFound_whenAbsent() {
        when(urlRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(LinkNotFoundException.class,
                () -> useCase.update("missing", new UpdateLinkRequest("https://example.com", null), "owner-a"));
    }

    @Test
    void delete_removesRowAndEvictsCache() {
        when(urlRepository.findById("abc123")).thenReturn(Optional.of(mapping()));
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("keys")).thenReturn(cache);

        useCase.delete("abc123", "owner-a");

        verify(urlRepository).deleteById("abc123");
        verify(cache).evict("abc123");
    }

    @Test
    void delete_throwsNotFound_whenAbsent() {
        when(urlRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(LinkNotFoundException.class, () -> useCase.delete("missing", "owner-a"));
    }
}
