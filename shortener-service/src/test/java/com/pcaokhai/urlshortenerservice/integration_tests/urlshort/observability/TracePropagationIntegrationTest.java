package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.dto.ShortenRequest;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proof that one request stays one trace across a service boundary.
 *
 * <p>A caller (nginx forwards whatever the client sent, so this stands in for the
 * gateway hop) sends POST /shorten carrying a W3C {@code traceparent}. keygen-service
 * is replaced by a real HTTP server on localhost so the outbound call goes over a
 * socket rather than through a stubbed exchange function — the header has to actually
 * be written onto the wire to be observed here.
 *
 * <p>What it asserts is the whole point of distributed tracing: the trace ID the caller
 * supplied is the trace ID keygen-service receives (so both services' logs and spans
 * join up), while the span ID differs (the keygen call is a child span, not the same
 * unit of work). Nothing in KeyGenClient sets these headers; Spring Boot's instrumented
 * WebClient does it because micrometer-tracing is on the classpath.
 */
@AutoConfigureMockMvc
@Import(TracePropagationIntegrationTest.NoCacheConfig.class)
@TestPropertySource(properties = {
        "management.tracing.sampling.probability=1.0",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "spring.main.allow-bean-definition-overriding=true"
})
class TracePropagationIntegrationTest extends BaseIntegrationTest {

    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INBOUND_SPAN_ID = "00f067aa0ba902b7";

    /** Header keygen-service saw, as sent by the shortener's WebClient. */
    private static final AtomicReference<String> keygenSawTraceparent = new AtomicReference<>();

    private static final HttpServer FAKE_KEYGEN = startFakeKeygen();

    static HttpServer startFakeKeygen() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/generate", exchange -> {
                keygenSawTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                byte[] body = "{\"shortKey\":\"tracedKey\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopFakeKeygen() {
        FAKE_KEYGEN.stop(0);
    }

    @DynamicPropertySource
    static void keygenUrl(DynamicPropertyRegistry reg) {
        reg.add("keygen.service.url", () -> "http://127.0.0.1:" + FAKE_KEYGEN.getAddress().getPort());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void traceIdFromTheCallerReachesKeygenService() throws Exception {
        String inbound = "00-" + INBOUND_TRACE_ID + "-" + INBOUND_SPAN_ID + "-01";
        // No alias: this is the path that actually calls keygen-service.
        String body = objectMapper.writeValueAsString(
                new ShortenRequest("https://example.com/traced", null));

        mockMvc.perform(post("/shorten")
                        .header("traceparent", inbound)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String outbound = keygenSawTraceparent.get();
        assertNotNull(outbound, "keygen-service received no traceparent header - the trace stops at the boundary");

        String[] parts = outbound.split("-");
        assertEquals(INBOUND_TRACE_ID, parts[1],
                "keygen-service saw a different trace ID; the two services' spans would never join up");
        assertNotEquals(INBOUND_SPAN_ID, parts[2],
                "the keygen call should be its own child span, not a reuse of the caller's span ID");

        System.out.printf("EVIDENCE  caller -> shortener: %s%n          shortener -> keygen:  %s%n",
                inbound, outbound);
    }

    @Test
    void prometheusEndpointServesMetrics() throws Exception {
        // The config-repo only flips the exposure list; this checks the registry and the
        // endpoint are actually on the classpath and wired, which is what makes that flip work.
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("jvm_memory_used_bytes"),
                "Prometheus scrape came back without the standard JVM metrics");
    }

    /** No Redis in this test; the trace, not the cache, is what is under test. */
    @TestConfiguration
    static class NoCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }
}
