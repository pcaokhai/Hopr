package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.outbox;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.CachePrimePoller;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.OutboxEvent;
import com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.UrlCreatedEventPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves {@link UrlCreatedEventPublisher} actually publishes to a real Kafka broker when
 * {@link CachePrimePoller} processes a PENDING outbox record -- the same producer-side
 * schema documented at {@code docs/events/url-created-event.json}.
 */
class UrlCreatedEventPublisherIntegrationTest extends BaseIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private CachePrimePoller cachePrimePoller;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        urlRepository.deleteAll();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        deleteAllInPartition(today, OutboxEvent.STATUS_PENDING);
        deleteAllInPartition(today, OutboxEvent.STATUS_PROCESSED);

        Map<String, Object> consumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(consumerProps);
        consumer.subscribe(java.util.List.of(UrlCreatedEventPublisher.TOPIC));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private void deleteAllInPartition(LocalDate bucket, String status) {
        Query query = Query.query(Criteria.where("bucket").is(bucket)).and(Criteria.where("status").is(status));
        cassandra.select(query, OutboxEvent.class).forEach(cassandra::delete);
    }

    @Test
    void pollerPublishesUrlCreatedEventForPendingOutboxRecord() throws Exception {
        UrlMapping mapping = new UrlMapping("kafka1", "https://example.com/kafka", "kafka1", null, Instant.now(), "ACTIVE");
        urlRepository.save(mapping);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OutboxEvent pending = new OutboxEvent(today, OutboxEvent.STATUS_PENDING, Instant.now(),
                Uuids.timeBased(), "kafka1", OutboxEvent.EVENT_TYPE_CACHE_PRIME, null);
        cassandra.insert(pending);

        cachePrimePoller.pollAndPrime();

        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, UrlCreatedEventPublisher.TOPIC, Duration.ofSeconds(10));
        assertNotNull(record);
        assertEquals("kafka1", record.key());

        var body = objectMapper.readTree(record.value());
        assertEquals("kafka1", body.get("shortKey").asText());
        assertEquals("https://example.com/kafka", body.get("longUrl").asText());
        assertEquals(1, body.get("eventVersion").asInt());
    }
}
