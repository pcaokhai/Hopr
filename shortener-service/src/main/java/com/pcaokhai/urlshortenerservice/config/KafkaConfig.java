package com.pcaokhai.urlshortenerservice.config;

import com.pcaokhai.common.event.UrlCreatedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Boot's auto-configured {@code KafkaTemplate<Object, Object>} bean doesn't satisfy an
 * injection point declared as {@code KafkaTemplate<String, UrlCreatedEvent>} -- generic type
 * matching for beans is invariant, and {@code Object} isn't a wildcard here. Building the
 * template explicitly off {@code spring.kafka.bootstrap-servers} keeps that type safety at the
 * one call site that needs it. {@code max.block.ms}/{@code delivery.timeout.ms} are kept short
 * so {@link com.pcaokhai.urlshortenerservice.urlshort.infra.outbox.UrlCreatedEventPublisher}'s
 * blocking {@code send().get()} can't stall {@code CachePrimePoller}'s scheduled run -- and
 * every other PENDING outbox event in it -- for the producer's default ~2min timeout during a
 * Kafka outage.
 */
@Configuration
public class KafkaConfig {

    private static final int MAX_BLOCK_MS = 2000;
    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int DELIVERY_TIMEOUT_MS = 5000;

    @Bean
    public ProducerFactory<String, UrlCreatedEvent> urlCreatedProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS));
    }

    @Bean
    public KafkaTemplate<String, UrlCreatedEvent> urlCreatedKafkaTemplate(
            ProducerFactory<String, UrlCreatedEvent> urlCreatedProducerFactory) {
        return new KafkaTemplate<>(urlCreatedProducerFactory);
    }
}
