package com.pcaokhai.clickanalyticsservice.config;

import com.pcaokhai.common.event.ClickEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * A poison message (one that fails to deserialize into a {@link ClickEvent}) is handled by
 * {@link ErrorHandlingDeserializer} plus the container's {@code DefaultErrorHandler} below --
 * both log the failure and move on to the next record rather than retrying or crashing the
 * listener container, since a malformed click-analytics message is not worth blocking the
 * partition over. See the PR description for the at-least-once delivery trade-off this
 * consumer accepts on the happy path.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConsumerFactory<String, ClickEvent> clickEventConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ClickEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.pcaokhai.common.event");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ClickEvent> clickEventListenerContainerFactory(
            ConsumerFactory<String, ClickEvent> clickEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, ClickEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(clickEventConsumerFactory);
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler(
                (record, ex) -> log.warn("Skipping unprocessable url-clicked record at offset={} partition={}",
                        record.offset(), record.partition(), ex),
                new FixedBackOff(0L, 0L)));
        return factory;
    }
}
