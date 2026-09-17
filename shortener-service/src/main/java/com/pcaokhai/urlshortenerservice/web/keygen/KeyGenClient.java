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
import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenTimeoutException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Client for interacting with the Keygen service to generate short keys.
 *
 * <p>The call is guarded twice. A reactive {@code .timeout()} bounds any single
 * call so a hung keygen-service cannot pin a Tomcat request thread forever, and a
 * Resilience4j circuit breaker ({@value #CIRCUIT_BREAKER}) trips after a run of
 * failures so subsequent callers fail fast instead of each paying the timeout.
 * The breaker is applied programmatically rather than with {@code @CircuitBreaker}:
 * Spring Boot 4 ships no AOP starter, so the annotation's aspect is not active here.
 * Thresholds live in {@code resilience4j.circuitbreaker} config; its state is
 * visible on {@code /actuator/health} and {@code /actuator/circuitbreakers}.
 */
@Component
public class KeyGenClient {
    public static final String CIRCUIT_BREAKER = "keygen";

    /**
     * keygen-service pops a pre-generated key off Redis, so a healthy call is
     * single-digit milliseconds. 800ms leaves ample headroom for a GC pause or a
     * DNS/connect hiccup while still being far below any human-perceptible stall,
     * and well below Tomcat's connection timeout.
     */
    static final Duration CALL_TIMEOUT = Duration.ofMillis(800);

    private static final Logger log = LoggerFactory.getLogger(KeyGenClient.class);

    private final WebClient webClient;
    private final String keygenServiceUrl;
    private final CircuitBreaker circuitBreaker;

    public KeyGenClient(WebClient.Builder builder,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        @Value("${keygen.service.url}") String keygenServiceUrl) {
        this.webClient = builder.build();
        this.keygenServiceUrl = keygenServiceUrl;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    public String generateKey() {
        try {
            return circuitBreaker.executeSupplier(this::callKeygenService);
        } catch (CallNotPermittedException e) {
            log.warn("keygen circuit breaker is open - failing fast without calling keygen-service");
            throw new KeygenServiceUnvailableException();
        } catch (KeygenTimeoutException e) {
            log.warn("keygen-service unavailable: {}", e.getMessage());
            throw new KeygenServiceUnvailableException();
        } catch (WebClientException e) {
            log.warn("keygen-service unavailable: call failed: {}", e.getMessage());
            throw new KeygenServiceUnvailableException();
        } catch (KeygenServiceUnvailableException e) {
            // Already diagnosed inside the call (e.g. a response with no shortKey).
            throw e;
        } catch (RuntimeException e) {
            // Not keygen being down - something in this code or in the response
            // contract is wrong. Log loudly with the stack trace instead of
            // burying a real bug under "keygen is unavailable".
            log.error("Unexpected failure while calling keygen-service", e);
            throw new KeygenServiceUnvailableException();
        }
    }

    private String callKeygenService() {
        String shortKey = webClient.get()
                .uri(keygenServiceUrl + "/generate")
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> (String) response.get("shortKey"))
                .timeout(CALL_TIMEOUT, Mono.error(() -> new KeygenTimeoutException(CALL_TIMEOUT)))
                .block();
        if (shortKey == null || shortKey.isBlank()) {
            log.warn("keygen-service returned a response without a usable shortKey");
            throw new KeygenServiceUnvailableException();
        }
        return shortKey;
    }
}
