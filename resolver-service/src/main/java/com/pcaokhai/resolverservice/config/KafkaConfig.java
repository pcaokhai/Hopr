package com.pcaokhai.resolverservice.config;

import com.pcaokhai.common.event.ClickEvent;
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
 * injection point declared as {@code KafkaTemplate<String, ClickEvent>} -- generic type
 * matching for beans is invariant. Building the template explicitly off
 * {@code spring.kafka.bootstrap-servers} keeps that type safety at the one call site that
 * needs it. {@code max.block.ms} is kept short so a synchronous {@code send()} call can never
 * itself block the redirect thread for long even before the async future is handled.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, ClickEvent> clickEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000));
    }

    @Bean
    public KafkaTemplate<String, ClickEvent> clickEventKafkaTemplate(
            ProducerFactory<String, ClickEvent> clickEventProducerFactory) {
        return new KafkaTemplate<>(clickEventProducerFactory);
    }
}
