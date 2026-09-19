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

import java.io.Serializable;
import java.util.List;

/**
 * A page of {@link #links()}, unscoped by owner (see the shortener-service PR that added this
 * endpoint for why: there is no owner index yet and any caller holding the API key can already
 * see every link). {@code nextPageToken} is Scylla's opaque native paging state, base64-encoded;
 * pass it back as the {@code pageToken} query parameter to fetch the next page, or {@code null}
 * when this is the last page.
 */
public record LinkListResponse(List<LinkResponse> links, String nextPageToken) implements Serializable {
}
