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
package com.pcaokhai.urlshortenerservice.urlshort.infra.router;

import com.pcaokhai.urlshortenerservice.security.ApiKeyFilter;
import com.pcaokhai.urlshortenerservice.urlshort.annotations.ShortenUrlOperation;
import com.pcaokhai.urlshortenerservice.urlshort.application.ShortenerUseCase;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.common.url.model.dto.ShortenResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** * Controller for handling URL shortening requests.
 * This controller processes incoming requests to shorten URLs and returns the shortened URL in the response.
 *
 * <p>Versioned in the URL path ({@code /v1/...}) so a future breaking change to the request or
 * response shape can ship as {@code /v2} alongside this one. The resolver's public redirect path
 * is deliberately left unversioned -- see its controller.
 */
@RestController
@RequestMapping("/v1/shorten")
public class ShortenerController {

    private final ShortenerUseCase shortenerUseCase;

    public ShortenerController(ShortenerUseCase shortenerUseCase) {
        this.shortenerUseCase = shortenerUseCase;
    }

    @PostMapping
    @ShortenUrlOperation
    public ResponseEntity<ShortenResponse> shortenUrl(
            @Valid @RequestBody ShortenRequest request,
            @RequestAttribute(ApiKeyFilter.OWNER_ID_ATTRIBUTE) String ownerId) {
        ShortenResponse shortUrl = shortenerUseCase.shorten(request, ownerId);
        return ResponseEntity.ok(shortUrl);
    }
}
