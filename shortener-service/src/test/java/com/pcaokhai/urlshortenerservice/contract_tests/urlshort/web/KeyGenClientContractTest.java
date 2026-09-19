package com.pcaokhai.urlshortenerservice.contract_tests.urlshort.web;

import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Consumer side of the {@code GET /generate} contract with keygen-service's
 * {@code KeyGenController}. Both sides read the same fixture in {@code docs/contracts/} — see
 * {@code docs/contracts/README.md}. This feeds keygen-service's exact response JSON into
 * {@link KeyGenClient} and asserts it still parses into the expected short key. keygen-service's
 * {@code KeyGenControllerContractTest} asserts the controller actually produces that JSON. A
 * shape change on either side that isn't mirrored on the other breaks one of the two tests.
 */
@SpringJUnitConfig
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
@TestPropertySource(properties = "keygen.service.url=http://keygen-service:8081")
class KeyGenClientContractTest {

    @Configuration
    static class TestConfig {
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder().exchangeFunction(request -> Mono.just(
                    ClientResponse.create(org.springframework.http.HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(readFixture())
                            .build()));
        }

        @Bean
        KeyGenClient keyGenClient(WebClient.Builder builder,
                                  CircuitBreakerRegistry registry,
                                  @Value("${keygen.service.url}") String url) {
            return new KeyGenClient(builder, registry, url);
        }

        private static String readFixture() {
            try {
                Path dir = Path.of("").toAbsolutePath();
                while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
                    dir = dir.getParent();
                }
                if (dir == null) {
                    throw new IllegalStateException("could not locate repo root from " + Path.of("").toAbsolutePath());
                }
                return Files.readString(dir.resolve("docs/contracts/keygen-generate-response.json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Autowired private KeyGenClient keyGenClient;

    @Test
    void generateKeyParsesTheSharedFixtureResponse() {
        assertEquals("aB3xY9z", keyGenClient.generateKey());
    }
}
