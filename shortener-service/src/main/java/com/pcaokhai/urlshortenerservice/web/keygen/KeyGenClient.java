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
package com.pcaokhai.urlshortenerservice.web.keygen;

import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenServiceUnvailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

/**
 * Client for interacting with the Keygen service to generate short keys.
 * This component uses WebClient to make HTTP requests to the Keygen service.
 */
@Component
public class KeyGenClient {
    private final WebClient webClient;
    private final String keygenServiceUrl;

    public KeyGenClient(WebClient.Builder builder, @Value("${keygen.service.url}") String keygenServiceUrl) {
        this.webClient = builder.build();
        this.keygenServiceUrl = keygenServiceUrl;
    }

    public String generateKey() {
        return callKeygenService()
                .orElseThrow(() -> new KeygenServiceUnvailableException("Keygen Service Is Unavailable"));
    }

    private Optional<String> callKeygenService() {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri(keygenServiceUrl + "/generate")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(response -> (String) response.get("shortKey"))
                    .block());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
