package com.pcaokhai.resolverservice.infra.kafka;

import com.pcaokhai.common.event.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes {@link ClickEvent} straight to Kafka after a successful redirect. There is no
 * durable write on this read path to hang an outbox record on (a redirect doesn't otherwise
 * write anything), so this is fire-and-forget: the returned future is logged on failure and
 * never awaited, so a Kafka outage can never delay or fail the 307 response. See the PR
 * description for why best-effort delivery is an acceptable trade-off for click analytics.
 */
@Component
public class ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickEventPublisher.class);
    public static final String TOPIC = "url-clicked";

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;

    public ClickEventPublisher(KafkaTemplate<String, ClickEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishClick(String shortKey) {
        try {
            kafkaTemplate.send(TOPIC, shortKey, ClickEvent.of(shortKey, Instant.now()))
                    .exceptionally(ex -> {
                        log.warn("Failed to publish UrlClicked event for shortKey={}", shortKey, ex);
                        return null;
                    });
        } catch (Exception e) {
            // KafkaTemplate#send itself can throw synchronously (e.g. serialization error,
            // producer already closed) before returning a future -- caught here so the redirect
            // response is never at risk regardless of where the failure happens.
            log.warn("Failed to publish UrlClicked event for shortKey={}", shortKey, e);
        }
    }
}
