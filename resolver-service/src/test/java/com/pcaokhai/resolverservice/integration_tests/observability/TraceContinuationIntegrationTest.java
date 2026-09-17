package com.pcaokhai.resolverservice.integration_tests.observability;

import com.pcaokhai.resolverservice.integration_tests.config.BaseIntegrationTest;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Gateway -> Resolver half of the trace. Resolver is a leaf service: it calls
 * Redis and Scylla, not another HTTP service, so "propagation" here means continuing
 * the caller's trace rather than starting a fresh one.
 *
 * <p>A filter placed inside Spring's observation filter reads the span that is current
 * while the controller runs — the same span ID and trace ID that reach the logs through
 * the MDC — and the test asserts its trace ID is the one the caller sent. Get this
 * wrong and every resolve shows up as an orphan trace with no link to the request that
 * caused it.
 */
@AutoConfigureMockMvc
@Import(TraceContinuationIntegrationTest.SpanProbe.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class TraceContinuationIntegrationTest extends BaseIntegrationTest {

    private static final String INBOUND_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private static final AtomicReference<String> traceIdDuringRequest = new AtomicReference<>();

    @TestConfiguration
    static class SpanProbe {
        /** Lowest precedence: runs inside the observation filter, so a span is active. */
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        Filter spanProbeFilter(Tracer tracer) {
            return (request, response, chain) -> {
                traceIdDuringRequest.set(tracer.currentSpan() == null
                        ? null : tracer.currentSpan().context().traceId());
                chain.doFilter(request, response);
            };
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean
    private com.pcaokhai.resolverservice.resolver.application.ResolverUseCase resolverUseCase;

    @Test
    void resolveContinuesTheCallersTrace() throws Exception {
        when(resolverUseCase.resolve("traced")).thenReturn("https://example.com");

        mockMvc.perform(get("/traced")
                        .header("traceparent", "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isTemporaryRedirect());

        assertNotNull(traceIdDuringRequest.get(), "no span was active during the request");
        assertEquals(INBOUND_TRACE_ID, traceIdDuringRequest.get(),
                "resolver started a new trace instead of continuing the caller's");

        System.out.println("EVIDENCE resolver span trace ID = " + traceIdDuringRequest.get());
    }
}
