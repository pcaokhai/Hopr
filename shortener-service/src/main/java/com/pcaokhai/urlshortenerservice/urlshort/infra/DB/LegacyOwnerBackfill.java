/*
 * Copyright 2025 the original author.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pcaokhai.urlshortenerservice.urlshort.infra.DB;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stamps {@link #LEGACY_OWNER_ID} onto every {@code urls} row written before per-owner scoping
 * existed, i.e. every row whose {@code owner_id} is null.
 *
 * <p>Without this, a link created by the previously-deployed build keeps resolving but matches
 * no owner, so it is invisible and unmanageable through {@code /v1/links} forever. Those links
 * are assigned to the owner id {@code legacy}: configure a key for that owner (a
 * {@code <digest>:legacy} entry in {@code SHORTENER_API_KEY_OWNERS}) and it manages all of them.
 *
 * <p>Not a Flyway CQL migration: Scylla cannot express "update the rows where a non-key column
 * is null" -- an UPDATE needs the full primary key and cannot filter on {@code owner_id} -- so
 * the rows have to be read first and written back one primary key at a time.
 *
 * <p>Safe to run repeatedly: a row that already has an owner is skipped, so the second run is a
 * read-only scan. Disable it with {@code shortener.legacy-owner-backfill.enabled=false} once no
 * null-owner rows remain, since the scan reads the whole table on every boot.
 */
@Component
@ConditionalOnProperty(name = "shortener.legacy-owner-backfill.enabled", matchIfMissing = true)
public class LegacyOwnerBackfill implements ApplicationRunner {

    public static final String LEGACY_OWNER_ID = "legacy";

    private static final Logger log = LoggerFactory.getLogger(LegacyOwnerBackfill.class);
    private static final int PAGE_SIZE = 500;

    private final CqlSession session;

    public LegacyOwnerBackfill(CqlSession session) {
        this.session = session;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Backfilling owner_id={} onto pre-scoping urls rows", LEGACY_OWNER_ID);
        // TTL(long_url) carries the row's remaining lifetime: a plain UPDATE writes owner_id
        // without a TTL, which would outlive the rest of an expiring row and leave a zombie
        // behind. `USING TTL 0` on a row that never expires means exactly "no TTL".
        PreparedStatement update = session.prepare(
                "UPDATE urls USING TTL ? SET owner_id = ? WHERE short_key = ?");
        int stamped = 0;
        Iterable<Row> rows = session.execute(
                SimpleStatement.newInstance("SELECT short_key, owner_id, TTL(long_url) AS ttl FROM urls")
                        .setPageSize(PAGE_SIZE));
        for (Row row : rows) {
            if (row.getString("owner_id") != null) {
                continue;
            }
            Integer ttl = row.isNull("ttl") ? null : row.getInt("ttl");
            session.execute(update.bind(ttl == null ? 0 : ttl, LEGACY_OWNER_ID, row.getString("short_key")));
            stamped++;
        }
        log.info("Legacy owner backfill complete: {} row(s) assigned to owner {}", stamped, LEGACY_OWNER_ID);
    }
}
