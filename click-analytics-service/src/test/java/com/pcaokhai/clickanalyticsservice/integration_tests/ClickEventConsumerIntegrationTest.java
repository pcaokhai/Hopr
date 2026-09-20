package com.pcaokhai.clickanalyticsservice.integration_tests;

import com.pcaokhai.clickanalyticsservice.analytics.infra.kafka.ClickEventConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ClickEventConsumer} turns a real {@code url-clicked} message (the schema
 * documented at {@code docs/events/url-clicked-event.json}, the same fixture producer-side
 * tests in resolver-service assert against) into both a counter increment on
 * {@code url_click_counts} and a row in {@code url_click_events}, and that a malformed
 * message on the topic does not stop later, well-formed messages from still being processed.
 */
class ClickEventConsumerIntegrationTest extends BaseIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private CassandraOperations cassandra;

    private org.apache.kafka.clients.producer.Producer<String, String> producer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(producerProps);
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    @Test
    void clickEventIncrementsCounterAndAppendsEventRow() {
        String shortKey = "int-" + UUID.randomUUID();
        String clickedAt = "2026-01-15T10:30:00Z";
        String payload = """
                {"eventVersion":1,"shortKey":"%s","clickedAt":"%s"}
                """.formatted(shortKey, clickedAt);

        producer.send(new ProducerRecord<>(ClickEventConsumer.TOPIC, shortKey, payload));

        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Long clicks = cassandra.getCqlOperations().queryForObject(
                    "SELECT clicks FROM url_click_counts WHERE short_key = ?", Long.class, shortKey);
            assertThat(clicks).isEqualTo(1L);

            Query query = Query.query(
                    Criteria.where("short_key").is(shortKey),
                    Criteria.where("day").is(LocalDate.of(2026, 1, 15)));
            List<com.pcaokhai.clickanalyticsservice.analytics.domain.ClickEventRow> rows =
                    cassandra.select(query, com.pcaokhai.clickanalyticsservice.analytics.domain.ClickEventRow.class);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getClickedAt()).isEqualTo(java.time.Instant.parse(clickedAt));
        });
    }

    @Test
    void malformedMessageIsSkippedWithoutStoppingLaterMessages() {
        producer.send(new ProducerRecord<>(ClickEventConsumer.TOPIC, "poison-key", "not valid json"));

        String shortKey = "int-" + UUID.randomUUID();
        String clickedAt = "2026-01-15T11:00:00Z";
        String payload = """
                {"eventVersion":1,"shortKey":"%s","clickedAt":"%s"}
                """.formatted(shortKey, clickedAt);
        producer.send(new ProducerRecord<>(ClickEventConsumer.TOPIC, shortKey, payload));

        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Long clicks = cassandra.getCqlOperations().queryForObject(
                    "SELECT clicks FROM url_click_counts WHERE short_key = ?", Long.class, shortKey);
            assertThat(clicks).isEqualTo(1L);
        });
    }
}
