package com.pcaokhai.clickanalyticsservice.analytics.domain;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A row in {@code url_click_events} (db-migration's V4 migration). The primary key is
 * {@code (short_key, day, clicked_at)} -- an at-least-once redelivery of the same
 * {@code ClickEvent} carries the same {@code clickedAt} the first delivery did, so the
 * redelivered write lands on the same primary key and simply overwrites the identical row
 * rather than creating a duplicate. That natural idempotency is why this table needs no
 * separate dedup mechanism, unlike {@code url_click_counts} (see {@link ClickAnalyticsRecorder}).
 */
@Table("url_click_events")
public class ClickEventRow {

    @PrimaryKeyColumn(name = "short_key", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String shortKey;

    @PrimaryKeyColumn(name = "day", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private LocalDate day;

    @PrimaryKeyColumn(name = "clicked_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant clickedAt;

    @Column("referrer")
    private String referrer;

    @Column("user_agent")
    private String userAgent;

    @Column("country")
    private String country;

    public ClickEventRow() {}

    public ClickEventRow(String shortKey, LocalDate day, Instant clickedAt,
                          String referrer, String userAgent, String country) {
        this.shortKey = shortKey;
        this.day = day;
        this.clickedAt = clickedAt;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.country = country;
    }

    public String getShortKey() { return shortKey; }

    public LocalDate getDay() { return day; }

    public Instant getClickedAt() { return clickedAt; }

    public String getReferrer() { return referrer; }

    public String getUserAgent() { return userAgent; }

    public String getCountry() { return country; }
}
