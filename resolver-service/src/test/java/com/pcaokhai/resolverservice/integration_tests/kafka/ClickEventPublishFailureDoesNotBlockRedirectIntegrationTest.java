package com.pcaokhai.resolverservice.integration_tests.kafka;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.resolverservice.integration_tests.config.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the redirect path degrades gracefully when Kafka is unreachable: the click-event
 * publish is fire-and-forget (see {@code ClickEventPublisher}), so a broker that never answers
 * must not block or fail the 307 response. Points {@code spring.kafka.bootstrap-servers} at a
 * non-routable address instead of the real broker used by the other tests in this package.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ClickEventPublishFailureDoesNotBlockRedirectIntegrationTest.RealRedisCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ClickEventPublishFailureDoesNotBlockRedirectIntegrationTest extends BaseIntegrationTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void unreachableKafka(DynamicPropertyRegistry registry) {
        // RFC 5737 TEST-NET-1: guaranteed non-routable, so the producer can never connect --
        // the closest thing to a real "Kafka is down" outage a unit-speed test can simulate.
        registry.add("spring.kafka.bootstrap-servers", () -> "192.0.2.1:9092");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void setUp() {
        urlRepository.deleteAll();
        urlRepository.save(new UrlMapping("degrade1", "https://example.com/degrade", "degrade1", null, Instant.now(), "ACTIVE"));
    }

    @Test
    void redirectSucceedsWithinBudgetEvenWhenKafkaIsUnreachable() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(get("/degrade1"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(redirectedUrl("https://example.com/degrade"));

        long elapsedMs = System.currentTimeMillis() - start;
        assertTrue(elapsedMs < 3000,
                "redirect must not be delayed by an unreachable Kafka broker, took " + elapsedMs + "ms");
    }

    @TestConfiguration
    static class RealRedisCacheConfig {
        @Bean
        CacheManager cacheManager() {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            return RedisCacheManager.builder(factory).build();
        }
    }
}
