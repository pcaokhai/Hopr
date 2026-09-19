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
package com.pcaokhai.common.url.model.dto;

import com.pcaokhai.common.url.model.UrlMapping;

import java.io.Serializable;
import java.time.Instant;

/**
 * A short link's current mapping and metadata, as returned by the list/get management endpoints.
 */
public record LinkResponse(
        String shortKey,
        String longUrl,
        String alias,
        String status,
        Instant createdAt,
        Instant expiresAt
) implements Serializable {

    public static LinkResponse from(UrlMapping mapping) {
        return new LinkResponse(mapping.getShortKey(), mapping.getLongUrl(), mapping.getAlias(),
                mapping.getStatus(), mapping.getCreatedAt(), mapping.getExpiresAt());
    }
}
