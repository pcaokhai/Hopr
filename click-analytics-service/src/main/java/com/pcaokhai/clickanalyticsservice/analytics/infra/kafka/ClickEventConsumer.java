package com.pcaokhai.clickanalyticsservice.analytics.infra.kafka;

import com.pcaokhai.clickanalyticsservice.analytics.application.ClickAnalyticsRecorder;
import com.pcaokhai.common.event.ClickEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code url-clicked} (published fire-and-forget by resolver-service's
 * {@code ClickEventPublisher}) entirely off the redirect path -- this service is the only
 * thing that ever consumes the topic, so a slow or failing analytics write can never add
 * latency or failure risk to serving a redirect. {@code UrlCreatedEvent}/{@code url-created}
 * has no listener here: nothing in {@code url_click_counts}/{@code url_click_events} has a
 * natural home for a "link was created" fact, so wiring it up would be a forced mapping just
 * to say both topics were used. See the PR description for the consumer-group reasoning
 * behind {@code GROUP_ID}.
 */
@Component
public class ClickEventConsumer {

    public static final String TOPIC = "url-clicked";

    /**
     * Stable, documented group id: every instance of this service shares it, so Kafka
     * assigns each partition to exactly one running instance and a future second replica
     * added for scaling divides the partitions between them instead of each one reprocessing
     * every record.
     */
    public static final String GROUP_ID = "click-analytics-service";

    private final ClickAnalyticsRecorder recorder;

    public ClickEventConsumer(ClickAnalyticsRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID, containerFactory = "clickEventListenerContainerFactory")
    public void onClickEvent(ClickEvent event) {
        recorder.record(event);
    }
}
