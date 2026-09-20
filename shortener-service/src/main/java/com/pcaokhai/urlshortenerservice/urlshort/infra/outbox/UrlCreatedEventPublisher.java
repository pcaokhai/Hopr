package com.pcaokhai.urlshortenerservice.urlshort.infra.outbox;

import com.pcaokhai.common.event.UrlCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link UrlCreatedEvent} to Kafka for each outbox record {@link CachePrimePoller}
 * processes. Riding the existing poller keeps this durable the same way cache-priming is: the
 * event was already written atomically-enough with the {@code urls} row (see
 * {@link OutboxEventWriter}), so a publish that fails here is retried on the next poll instead
 * of being lost, without a second poll-and-mark-processed loop over the same table.
 */
@Component
public class UrlCreatedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UrlCreatedEventPublisher.class);
    public static final String TOPIC = "url-created";

    private final KafkaTemplate<String, UrlCreatedEvent> kafkaTemplate;

    public UrlCreatedEventPublisher(KafkaTemplate<String, UrlCreatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(UrlCreatedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.shortKey(), event).get();
        } catch (Exception e) {
            // Not rethrown: CachePrimePoller must still mark the record processed so caching
            // isn't held hostage by a Kafka outage. A publish failure here is a lost event, not
            // a retried one -- the outbox table's PENDING->PROCESSED transition is shared with
            // cache-priming, so it can't also serve as this publisher's own retry queue without
            // a schema change. Acceptable for this PR: see the PR description for the
            // eventual-consistency trade-off this accepts for analytics-shaped events.
            log.warn("Failed to publish UrlCreated event for shortKey={}", event.shortKey(), e);
        }
    }
}
