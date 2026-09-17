package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.web;

import com.pcaokhai.urlshortenerservice.urlshort.exception.KeygenServiceUnvailableException;
import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the circuit breaker is actually wired around {@link KeyGenClient#generateKey()}:
 * once the configured failure threshold is crossed the breaker opens and further
 * callers fail fast without any further HTTP attempt against keygen-service.
 */
@SpringJUnitConfig
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
@TestPropertySource(properties = {
        "keygen.service.url=http://keygen-service:8081",
        "resilience4j.circuitbreaker.instances.keygen.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.keygen.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.keygen.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.keygen.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.keygen.wait-duration-in-open-state=10s"
})
class KeyGenClientCircuitBreakerTest {

    private static final int MINIMUM_CALLS = 5;

    /** Counts every HTTP attempt the client actually makes, and fails all of them. */
    static final AtomicInteger attempts = new AtomicInteger();

    @Configuration
    static class TestConfig {
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder().exchangeFunction(request -> {
                attempts.incrementAndGet();
                return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).build());
            });
        }

        @Bean
        KeyGenClient keyGenClient(WebClient.Builder builder,
                                  CircuitBreakerRegistry registry,
                                  @org.springframework.beans.factory.annotation.Value("${keygen.service.url}") String url) {
            return new KeyGenClient(builder, registry, url);
        }
    }

    @Autowired private KeyGenClient keyGenClient;
    @Autowired private CircuitBreakerRegistry registry;

    @Test
    void breakerOpensAfterThresholdAndStopsCallingKeygen() {
        attempts.set(0);
        CircuitBreaker breaker = registry.circuitBreaker(KeyGenClient.CIRCUIT_BREAKER);
        breaker.reset();

        for (int i = 0; i < MINIMUM_CALLS; i++) {
            assertThrows(KeygenServiceUnvailableException.class, () -> keyGenClient.generateKey());
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertEquals(MINIMUM_CALLS, attempts.get());

        // Breaker is open: this call must fail fast without touching keygen-service.
        assertThrows(KeygenServiceUnvailableException.class, () -> keyGenClient.generateKey());
        assertEquals(MINIMUM_CALLS, attempts.get());
    }
}
