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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * Represents a request to shorten a URL.
 * This record encapsulates the long URL to be shortened and an optional alias for the shortened URL.
 *
 * @param longUrl The original long URL that needs to be shortened. Restricted to absolute
 *                {@code http}/{@code https} URLs so a short link can never be turned into a
 *                redirect to {@code javascript:}, {@code data:} or any other scheme, and capped
 *                at {@value #MAX_LONG_URL_LENGTH} characters, the de-facto browser/CDN URL limit.
 * @param alias   An optional alias for the shortened URL, which can be used instead of a generated key.
 */
public record ShortenRequest(
        @NotBlank(message = "longUrl must not be blank")
        @Size(max = MAX_LONG_URL_LENGTH, message = "longUrl must be at most " + MAX_LONG_URL_LENGTH + " characters")
        @Pattern(regexp = HTTP_URL, message = "longUrl must be an absolute http or https URL")
        String longUrl,
        String alias
) implements Serializable {

    /** Longest URL we accept: the practical ceiling browsers and CDNs impose on a URL. */
    public static final int MAX_LONG_URL_LENGTH = 2048;

    /** Absolute http(s) URL with a non-empty, whitespace-free host. */
    private static final String HTTP_URL = "^(?i:https?)://[^\\s/?#]+[^\\s]*\\z";
}
