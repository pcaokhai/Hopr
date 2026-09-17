package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end view of the keygen guard from the caller's side: a real POST /shorten
 * through the running application, with keygen-service replaced by a stub that
 * either hangs forever or answers 503.
 *
 * <p>Evidence this produces: the HTTP status/body a client actually receives, how
 * long each request occupied the request thread, and the breaker state exposed by
 * the Resilience4j registry (the same state {@code /actuator/circuitbreakers} reports).
 */
@AutoConfigureMockMvc
@Import(KeygenResilienceIntegrationTest.StubKeygenTransport.class)
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.keygen.register-health-indicator=true",
        "resilience4j.circuitbreaker.instances.keygen.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.keygen.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.keygen.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.keygen.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.keygen.wait-duration-in-open-state=10s"
})
class KeygenResilienceIntegrationTest extends BaseIntegrationTest {

    /** Stands in for keygen-service; the mode decides how it (mis)behaves. */
    /** Mirrors KeyGenClient.CALL_TIMEOUT, which is package-private. */
    private static final long CALL_TIMEOUT_MILLIS = 800;

    enum Mode { HANG, FAIL }

    static volatile Mode mode = Mode.HANG;
    static final AtomicInteger attempts = new AtomicInteger();

    @TestConfiguration
    static class StubKeygenTransport {
        @Bean
        @Primary
        WebClient.Builder webClientBuilder() {
            return WebClient.builder().exchangeFunction(request -> {
                attempts.incrementAndGet();
                return mode == Mode.HANG
                        ? Mono.never()
                        : Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            });
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CircuitBreakerRegistry registry;

    private final StringBuilder transcript = new StringBuilder();

    @Test
    void hungKeygenIsBoundedByTheTimeoutAndThenShortCircuited() throws Exception {
        CircuitBreaker breaker = registry.circuitBreaker(KeyGenClient.CIRCUIT_BREAKER);
        breaker.reset();
        attempts.set(0);

        // 1. keygen accepts the request and never answers. Without a timeout this
        //    request would hold its Tomcat thread forever.
        mode = Mode.HANG;
        long hung = callShorten(breaker);
        assertTrue(hung < 5_000, "hung keygen should be abandoned by the client timeout, took " + hung + "ms");
        assertTrue(hung >= CALL_TIMEOUT_MILLIS,
                "request returned before the configured timeout elapsed: " + hung + "ms");

        // 2. Four more failures cross the 5-call minimum and the 50% failure rate.
        mode = Mode.FAIL;
        for (int i = 0; i < 4; i++) {
            callShorten(breaker);
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        int attemptsBeforeOpen = attempts.get();
        assertEquals(5, attemptsBeforeOpen);

        // 3. Breaker open: the next caller fails fast, with no call to keygen at all.
        long fastFail = callShorten(breaker);
        assertEquals(attemptsBeforeOpen, attempts.get(), "open breaker must not call keygen-service");
        assertTrue(fastFail < CALL_TIMEOUT_MILLIS,
                "open breaker should fail fast, took " + fastFail + "ms");

        System.out.println("\n=== POST /shorten transcript (keygen-service stubbed) ===\n" + transcript);
    }

    private long callShorten(CircuitBreaker breaker) throws Exception {
        String body = objectMapper.writeValueAsString(new ShortenRequest("https://example.com/a-long-url", null));
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        transcript.append(String.format(
                "POST /shorten  (keygen=%s, breaker=%s before call)%n  -> HTTP %d %s%n  -> %dms, keygen HTTP attempts so far: %d%n%n",
                mode, breaker.getState(), result.getResponse().getStatus(),
                result.getResponse().getContentAsString(), elapsed, attempts.get()));
        assertEquals(503, result.getResponse().getStatus());
        return elapsed;
    }
}
