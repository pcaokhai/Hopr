package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.application;

import com.pcaokhai.urlshortenerservice.config.ShortenerProperties;
import com.pcaokhai.urlshortenerservice.urlshort.application.AliasValidationComposite;
import com.pcaokhai.urlshortenerservice.urlshort.application.KeyGenResolver;
import com.pcaokhai.urlshortenerservice.urlshort.application.ShortenerUseCase;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.model.dto.ShortenResponse;
import com.pcaokhai.urlshortenerservice.urlshort.exception.AliasNotAvailableException;
import com.pcaokhai.urlshortenerservice.urlshort.infra.DB.DbCacheSaver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ShortenerUseCaseTest {

    @Mock
    private ShortenerProperties domainProperties;

    @Mock
    private AliasValidationComposite aliasValidation;

    @Mock
    private KeyGenResolver keyGenResolver;

    @Mock
    private DbCacheSaver dbCacheSaver;

    @InjectMocks
    private ShortenerUseCase useCase;

    @Test
    void shorten_withValidAlias_returnsShortUrl() {
        String alias = "myAlias";
        String longUrl = "https://example.com";
        ShortenRequest req = new ShortenRequest(longUrl, alias);
        when(domainProperties.toString()).thenReturn("https://short.ly/");
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(true);
        ShortenResponse resp = useCase.shorten(req, "owner-a");
        assertEquals("https://short.ly/" + alias, resp.shortUrl());
        verify(aliasValidation).validate(alias);
        verifyNoInteractions(keyGenResolver);
        verify(dbCacheSaver).saveUrlMappingIfAbsent(
                argThat(mapping ->
                        mapping.getShortKey().equals(alias) &&
                                mapping.getLongUrl().equals(longUrl) &&
                                mapping.getAlias().equals(alias)
                ),
                any()
        );
    }

    @Test
    void shorten_whenAliasAlreadyClaimed_throwsAliasNotAvailable() {
        String alias = "taken";
        ShortenRequest req = new ShortenRequest("https://example.com", alias);
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(false);
        assertThrows(AliasNotAvailableException.class, () -> useCase.shorten(req, "owner-a"));
        verify(dbCacheSaver).saveUrlMappingIfAbsent(any(), any());
        verifyNoInteractions(keyGenResolver);
    }

    @Test
    void shorten_whenAliasInvalid_throwsAndSkipsGeneration() {
        String alias = "bad!";
        ShortenRequest req = new ShortenRequest("https://x.com", alias);
        doThrow(new IllegalArgumentException("Invalid"))
                .when(aliasValidation).validate(alias);
        assertThrows(IllegalArgumentException.class, () -> useCase.shorten(req, "owner-a"));
        verify(aliasValidation).validate(alias);
        verifyNoInteractions(keyGenResolver);
        verifyNoInteractions(dbCacheSaver);
    }

    @Test
    void shorten_withoutAlias_generatesKeyAndClaimsIt() {
        String generated = "abc123";
        ShortenRequest req = new ShortenRequest("https://foo.com", null);
        when(keyGenResolver.resolveShortKey()).thenReturn(generated);
        when(domainProperties.toString()).thenReturn("https://short.ly/");
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(true);
        ShortenResponse resp = useCase.shorten(req, "owner-a");
        assertEquals("https://short.ly/" + generated, resp.shortUrl());
        verify(aliasValidation).validate(null);
        verify(dbCacheSaver).saveUrlMappingIfAbsent(
                argThat(mapping ->
                        mapping.getShortKey().equals(generated) &&
                                mapping.getLongUrl().equals("https://foo.com") &&
                                mapping.getAlias() == null
                ),
                any()
        );
    }

    @Test
    void shorten_whenGeneratedKeyCollides_retriesWithAFreshKey() {
        ShortenRequest req = new ShortenRequest("https://foo.com", null);
        when(keyGenResolver.resolveShortKey()).thenReturn("taken", "free");
        when(domainProperties.toString()).thenReturn("https://short.ly/");
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(false, true);
        assertEquals("https://short.ly/free", useCase.shorten(req, "owner-a").shortUrl());
        verify(keyGenResolver, times(2)).resolveShortKey();
    }

    @Test
    void shorten_withExpiresInSeconds_setsExpiresAtAndPassesTtlThrough() {
        String alias = "expiring";
        long ttlSeconds = 60L;
        ShortenRequest req = new ShortenRequest("https://example.com", alias, ttlSeconds);
        when(domainProperties.toString()).thenReturn("https://short.ly/");
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(true);

        useCase.shorten(req, "owner-a");

        verify(dbCacheSaver).saveUrlMappingIfAbsent(
                argThat(mapping -> mapping.getExpiresAt() != null
                        && mapping.getExpiresAt().isAfter(java.time.Instant.now())),
                eq(ttlSeconds)
        );
    }

    @Test
    void shorten_whenEveryGeneratedKeyCollides_fails() {
        ShortenRequest req = new ShortenRequest("https://foo.com", null);
        when(keyGenResolver.resolveShortKey()).thenReturn("taken");
        when(dbCacheSaver.saveUrlMappingIfAbsent(any(), any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> useCase.shorten(req, "owner-a"));
        verify(dbCacheSaver, times(5)).saveUrlMappingIfAbsent(any(), any());
    }

}
