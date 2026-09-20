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

import com.pcaokhai.common.url.model.dto.LinkListResponse;
import com.pcaokhai.common.url.model.dto.LinkResponse;
import com.pcaokhai.common.url.model.dto.UpdateLinkRequest;
import com.pcaokhai.urlshortenerservice.security.ApiKeyFilter;
import com.pcaokhai.urlshortenerservice.urlshort.application.LinkManagementUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management endpoints for links already created via {@code POST /v1/shorten}: list, read, update
 * (long URL and/or expiration), and delete. Gated by the same {@code X-API-Key} filter as
 * {@code /v1/shorten} (see {@link com.pcaokhai.urlshortenerservice.security.ApiKeySecurityConfig}),
 * and scoped to the owner that filter resolved from the key: a caller only ever reaches links its
 * own key created, and another owner's link is a 404.
 */
@RestController
@RequestMapping("/v1/links")
public class LinkManagementController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final LinkManagementUseCase linkManagementUseCase;

    public LinkManagementController(LinkManagementUseCase linkManagementUseCase) {
        this.linkManagementUseCase = linkManagementUseCase;
    }

    @GetMapping
    public ResponseEntity<LinkListResponse> list(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(required = false) String pageToken,
            @RequestAttribute(ApiKeyFilter.OWNER_ID_ATTRIBUTE) String ownerId) {
        return ResponseEntity.ok(linkManagementUseCase.list(pageSize, pageToken, ownerId));
    }

    @GetMapping("/{shortKey}")
    public ResponseEntity<LinkResponse> get(@PathVariable String shortKey,
            @RequestAttribute(ApiKeyFilter.OWNER_ID_ATTRIBUTE) String ownerId) {
        return ResponseEntity.ok(linkManagementUseCase.get(shortKey, ownerId));
    }

    @PatchMapping("/{shortKey}")
    public ResponseEntity<LinkResponse> update(@PathVariable String shortKey,
            @Valid @RequestBody UpdateLinkRequest request,
            @RequestAttribute(ApiKeyFilter.OWNER_ID_ATTRIBUTE) String ownerId) {
        return ResponseEntity.ok(linkManagementUseCase.update(shortKey, request, ownerId));
    }

    @DeleteMapping("/{shortKey}")
    public ResponseEntity<Void> delete(@PathVariable String shortKey,
            @RequestAttribute(ApiKeyFilter.OWNER_ID_ATTRIBUTE) String ownerId) {
        linkManagementUseCase.delete(shortKey, ownerId);
        return ResponseEntity.noContent().build();
    }
}
