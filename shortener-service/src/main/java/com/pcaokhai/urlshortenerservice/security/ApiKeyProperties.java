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
package com.pcaokhai.urlshortenerservice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The API keys accepted on {@code /v1/shorten} and {@code /v1/links}, each mapped to the owner it
 * identifies. Configured as {@code <sha-256 hex digest>:<owner-id>} entries -- only digests,
 * never the keys themselves, so a leaked config file, config-server response, or process dump
 * yields nothing a caller can replay against the API.
 *
 * <p>The owner id is what turns authentication ("this is a key we issued") into authorization
 * scoping ("this is <em>whose</em> key it is"): it is stamped onto every link the key creates
 * and every management query it makes.
 *
 * <p>A flat list of {@code hash:owner} strings rather than a YAML map, because the value
 * arrives as a single environment variable (see {@code SHORTENER_API_KEY_OWNERS}) and Spring
 * cannot bind one of those into a map.
 */
@Component
@ConfigurationProperties(prefix = "shortener.api-key")
public class ApiKeyProperties {

    private List<String> owners = List.of();

    public List<String> getOwners() { return owners; }

    public void setOwners(List<String> owners) {
        this.owners = owners == null ? List.of() : owners;
    }

    /** Parsed {@code hash -> owner id}, preserving configuration order. */
    public Map<String, String> ownerByHash() {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : owners) {
            int separator = entry.lastIndexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                        "shortener.api-key.owners entries must be `<sha-256 hex digest>:<owner-id>`, got: " + entry);
            }
            parsed.put(entry.substring(0, separator).strip(), entry.substring(separator + 1).strip());
        }
        return parsed;
    }
}
