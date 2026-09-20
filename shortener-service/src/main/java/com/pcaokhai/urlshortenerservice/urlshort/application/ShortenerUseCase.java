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
import com.pcaokhai.urlshortenerservice.config.ShortenerProperties;
import com.pcaokhai.common.url.model.dto.ShortenResponse;
import com.pcaokhai.urlshortenerservice.urlshort.infra.DB.DbCacheSaver;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.urlshortenerservice.urlshort.exception.AliasNotAvailableException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Use case for shortening URLs.
 * This service handles the logic for creating a short URL from a long URL and an optional alias.
 * It validates the alias, generates a short key, builds a URL mapping, and persists it in the database.
 *
 */
@Service
public class ShortenerUseCase {

    private final ShortenerProperties domainProperties;
    private final AliasValidationComposite aliasValidation;
    private final KeyGenResolver keyGenResolver;
    private final DbCacheSaver dbCacheSaver;

    public ShortenerUseCase(ShortenerProperties domainProperties, AliasValidationComposite aliasValidation, KeyGenResolver keyGenResolver, DbCacheSaver dbCacheSaver) {
        this.domainProperties = domainProperties;
        this.aliasValidation = aliasValidation;
        this.keyGenResolver = keyGenResolver;
        this.dbCacheSaver = dbCacheSaver;
    }

    private static final int MAX_GENERATED_KEY_ATTEMPTS = 5;

    public ShortenResponse shorten(ShortenRequest request, String ownerId) {
        validateAlias(request.alias());
        String shortKey = claimShortKey(request, ownerId);
        return buildShortUrl(shortKey);
    }

    private void validateAlias(String alias) {
        aliasValidation.validate(alias);
    }

    // Both a user-chosen alias and a generated key can collide with an already claimed row, so
    // every write goes through INSERT ... IF NOT EXISTS: the storage layer arbitrates uniqueness,
    // a preceding existence check could only ever narrow the race window, never close it.
    // A losing alias is the caller's problem (409); a losing generated key is an internal
    // collision the caller cannot influence, so we simply ask keygen for another one.
    private String claimShortKey(ShortenRequest request, String ownerId) {
        String alias = request.alias();
        if (StringUtils.hasText(alias)) {
            if (!dbCacheSaver.saveUrlMappingIfAbsent(buildMapping(alias, request, ownerId), request.expiresInSeconds())) {
                throw new AliasNotAvailableException("Alias " + alias + " is not available");
            }
            return alias;
        }
        for (int attempt = 0; attempt < MAX_GENERATED_KEY_ATTEMPTS; attempt++) {
            String shortKey = keyGenResolver.resolveShortKey();
            if (dbCacheSaver.saveUrlMappingIfAbsent(buildMapping(shortKey, request, ownerId), request.expiresInSeconds())) {
                return shortKey;
            }
        }
        throw new IllegalStateException(
                "Could not claim a free short key after " + MAX_GENERATED_KEY_ATTEMPTS + " attempts");
    }

    static final String STATUS_ACTIVE = "ACTIVE";

    private UrlMapping buildMapping(String shortKey, ShortenRequest request, String ownerId) {
        Instant expiresAt = request.expiresInSeconds() == null
                ? null
                : Instant.now().plusSeconds(request.expiresInSeconds());
        UrlMapping mapping =
                new UrlMapping(shortKey, request.longUrl(), request.alias(), expiresAt, Instant.now(), STATUS_ACTIVE);
        // Stamped from the authenticated caller's API key, never from the request body: an owner
        // a client could name is an owner a client could impersonate.
        mapping.setOwnerId(ownerId);
        return mapping;
    }

    private ShortenResponse buildShortUrl(String shortKey) {
        return new ShortenResponse( domainProperties + shortKey);
    }
}
