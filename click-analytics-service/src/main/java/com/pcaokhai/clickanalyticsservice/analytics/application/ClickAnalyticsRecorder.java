package com.pcaokhai.clickanalyticsservice.analytics.application;

import com.pcaokhai.clickanalyticsservice.analytics.domain.ClickEventRow;
import com.pcaokhai.common.event.ClickEvent;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Writes a {@link ClickEvent} into the two Phase 1 analytics tables. {@code url_click_counts}
 * is a plain {@code UPDATE ... SET clicks = clicks + 1} -- Scylla counters have no
 * "increment exactly once" mode, so at-least-once redelivery of the same event double-counts
 * a click here. {@code url_click_events} does not have this problem: its primary key already
 * dedupes a redelivery (see {@link ClickEventRow}). See the PR description for why this
 * asymmetry between the two tables is an accepted trade-off rather than something this PR
 * builds dedup machinery to close.
 */
@Component
public class ClickAnalyticsRecorder {

    private static final String INCREMENT_CLICKS_CQL =
            "UPDATE url_click_counts SET clicks = clicks + 1 WHERE short_key = ?";

    private final CassandraOperations cassandra;

    public ClickAnalyticsRecorder(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    public void record(ClickEvent event) {
        cassandra.getCqlOperations().execute(INCREMENT_CLICKS_CQL, event.shortKey());
        LocalDate day = LocalDate.ofInstant(event.clickedAt(), ZoneOffset.UTC);
        cassandra.insert(new ClickEventRow(event.shortKey(), day, event.clickedAt(), null, null, null));
    }
}
