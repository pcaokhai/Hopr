package com.pcaokhai.common.event;

import java.time.Instant;

/**
 * Published to the {@code url-clicked} Kafka topic after a redirect succeeds. Unlike
 * {@link UrlCreatedEvent}, there is no durable write on the redirect path to hang an outbox
 * record on, so this is published directly, best-effort -- see the PR description for why
 * that asymmetry is acceptable for click analytics but not for the URL mapping itself.
 * Schema documented at {@code docs/events/url-clicked-event.json}.
 */
public record ClickEvent(
        int eventVersion,
        String shortKey,
        Instant clickedAt) {

    public static final int CURRENT_VERSION = 1;

    public static ClickEvent of(String shortKey, Instant clickedAt) {
        return new ClickEvent(CURRENT_VERSION, shortKey, clickedAt);
    }
}
