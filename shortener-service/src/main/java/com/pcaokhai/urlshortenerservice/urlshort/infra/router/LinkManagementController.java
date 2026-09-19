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
import com.pcaokhai.urlshortenerservice.urlshort.application.LinkManagementUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management endpoints for links already created via {@code POST /shorten}: list, read, update
 * (long URL and/or expiration), and delete. Gated by the same {@code X-API-Key} filter as
 * {@code /shorten} (see {@link com.pcaokhai.urlshortenerservice.security.ApiKeySecurityConfig}) --
 * scoped identically, i.e. not scoped at all: any caller holding the key can manage any link.
 */
@RestController
@RequestMapping("/links")
public class LinkManagementController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final LinkManagementUseCase linkManagementUseCase;

    public LinkManagementController(LinkManagementUseCase linkManagementUseCase) {
        this.linkManagementUseCase = linkManagementUseCase;
    }

    @GetMapping
    public ResponseEntity<LinkListResponse> list(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(required = false) String pageToken) {
        return ResponseEntity.ok(linkManagementUseCase.list(pageSize, pageToken));
    }

    @GetMapping("/{shortKey}")
    public ResponseEntity<LinkResponse> get(@PathVariable String shortKey) {
        return ResponseEntity.ok(linkManagementUseCase.get(shortKey));
    }

    @PatchMapping("/{shortKey}")
    public ResponseEntity<LinkResponse> update(@PathVariable String shortKey, @Valid @RequestBody UpdateLinkRequest request) {
        return ResponseEntity.ok(linkManagementUseCase.update(shortKey, request));
    }

    @DeleteMapping("/{shortKey}")
    public ResponseEntity<Void> delete(@PathVariable String shortKey) {
        linkManagementUseCase.delete(shortKey);
        return ResponseEntity.noContent().build();
    }
}
