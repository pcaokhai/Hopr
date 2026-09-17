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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * keygen-service is reachable and fast but answers with a changed response contract.
 * That is a bug on the response shape, not keygen being down, so it must not count
 * towards the breaker's failure rate.
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
class KeyGenClientResponseShapeTest {

    @Configuration
    static class TestConfig {
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder().exchangeFunction(request -> Mono.just(
                    ClientResponse.create(org.springframework.http.HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"shortKey\": 123}")
                            .build()));
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
    void badResponseShapeDoesNotOpenTheBreaker() {
        CircuitBreaker breaker = registry.circuitBreaker(KeyGenClient.CIRCUIT_BREAKER);
        breaker.reset();

        for (int i = 0; i < 10; i++) {
            assertThrows(KeygenServiceUnvailableException.class, () -> keyGenClient.generateKey());
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }
}
