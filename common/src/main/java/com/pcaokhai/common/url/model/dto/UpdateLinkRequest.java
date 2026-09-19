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

import com.pcaokhai.common.url.model.dto.validation.ParseableUri;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

import static com.pcaokhai.common.url.model.dto.ShortenRequest.HTTP_URL;
import static com.pcaokhai.common.url.model.dto.ShortenRequest.MAX_LONG_URL_LENGTH;

/**
 * Partial update for an existing short link. Both fields are optional so a caller can change
 * only the long URL, only the expiration, or both; a null field leaves the current value alone.
 *
 * @param longUrl          New destination URL, re-validated with the same rules as {@link ShortenRequest#longUrl()}.
 * @param expiresInSeconds New lifetime in seconds from now; omit to leave the current expiration alone.
 */
public record UpdateLinkRequest(
        @Size(max = MAX_LONG_URL_LENGTH, message = "longUrl must be at most " + MAX_LONG_URL_LENGTH + " characters")
        @Pattern(regexp = HTTP_URL, message = "longUrl must be an absolute http or https URL")
        @ParseableUri(message = "longUrl must be a parseable URI")
        String longUrl,
        @Positive(message = "expiresInSeconds must be a positive number of seconds")
        Long expiresInSeconds
) implements Serializable {
}
