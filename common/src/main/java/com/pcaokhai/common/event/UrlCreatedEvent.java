package com.pcaokhai.common.event;

import java.time.Instant;

/**
 * Published to the {@code url-created} Kafka topic when a short link is durably persisted.
 * Schema documented at {@code docs/events/url-created-event.json}; bump {@code eventVersion}
 * on any incompatible field change so consumers can branch on it.
 */
public record UrlCreatedEvent(
        int eventVersion,
        String shortKey,
        String longUrl,
        Instant createdAt) {

    public static final int CURRENT_VERSION = 1;

    public static UrlCreatedEvent of(String shortKey, String longUrl, Instant createdAt) {
        return new UrlCreatedEvent(CURRENT_VERSION, shortKey, longUrl, createdAt);
    }
}
