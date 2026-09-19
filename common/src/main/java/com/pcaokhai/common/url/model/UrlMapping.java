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
package com.pcaokhai.common.url.model;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a URL mapping in the system.
 * This class is used to store the mapping between a short key and a long URL,
 * along with an optional alias for the URL.
 *
 */
@Table("urls")
public class UrlMapping implements Serializable {

    // Column names are spelled out because the CQL table (db-migration V2) uses
    // snake_case, while Spring Data Cassandra would otherwise derive `shortkey`
    // and `longurl` from the property names.
    @PrimaryKey("short_key")
    private String shortKey;

    @Column("long_url")
    private String longUrl;

    @Column("alias")
    private String alias;

    // Read-visibility only: the row's actual removal is driven by ScyllaDB's own per-row TTL
    // set on the INSERT (see shortener-service's DbCacheSaver), not by this column. Kept in
    // sync with that TTL so a response or future management UI can show when a link expires
    // without needing a second, TTL-derived source of truth.
    @Column("expires_at")
    private Instant expiresAt;

    public UrlMapping() {}

    public UrlMapping(String shortKey, String longUrl, String alias) {
        this(shortKey, longUrl, alias, null);
    }

    @PersistenceCreator
    public UrlMapping(String shortKey, String longUrl, String alias, Instant expiresAt) {
        this.shortKey = shortKey;
        this.longUrl = longUrl;
        this.alias = alias;
        this.expiresAt = expiresAt;
    }

    public String getShortKey() {
        return shortKey;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public String getAlias() {return alias;}

    public Instant getExpiresAt() {return expiresAt;}

    public void setShortKey(String shortKey) {
        this.shortKey = shortKey;
    }

    public void setLongUrl(String longUrl) {this.longUrl = longUrl;}

    public void setAlias(String alias) {this.alias = alias;}

    public void setExpiresAt(Instant expiresAt) {this.expiresAt = expiresAt;}

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        UrlMapping that = (UrlMapping) o;
        return Objects.equals(shortKey, that.shortKey) && Objects.equals(longUrl, that.longUrl)
                && Objects.equals(alias, that.alias) && Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortKey, longUrl, alias, expiresAt);
    }
}
