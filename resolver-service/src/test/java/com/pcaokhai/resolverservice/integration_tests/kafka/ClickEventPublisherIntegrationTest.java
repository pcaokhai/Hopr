package com.pcaokhai.resolverservice.integration_tests.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.resolverservice.infra.kafka.ClickEventPublisher;
import com.pcaokhai.resolverservice.integration_tests.config.BaseIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@link ClickEventPublisher} publishes a real {@code url-clicked} Kafka message after a
 * successful redirect -- the producer-side schema documented at
 * {@code docs/events/url-clicked-event.json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ClickEventPublisherIntegrationTest.RealRedisCacheConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ClickEventPublisherIntegrationTest extends BaseIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlRepository urlRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        urlRepository.deleteAll();
        Map<String, Object> consumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "resolver-test-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(List.of(ClickEventPublisher.TOPIC));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void successfulRedirectPublishesClickEvent() throws Exception {
        UrlMapping mapping = new UrlMapping("clicked1", "https://example.com/clicked", "clicked1", null, Instant.now(), "ACTIVE");
        urlRepository.save(mapping);

        mockMvc.perform(get("/clicked1"))
                .andExpect(status().isTemporaryRedirect());

        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, ClickEventPublisher.TOPIC, Duration.ofSeconds(10));
        assertNotNull(record);
        assertEquals("clicked1", record.key());

        var body = objectMapper.readTree(record.value());
        assertEquals("clicked1", body.get("shortKey").asText());
        assertEquals(1, body.get("eventVersion").asInt());
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
