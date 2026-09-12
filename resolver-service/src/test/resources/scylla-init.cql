CREATE KEYSPACE IF NOT EXISTS hopr WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1} AND tablets = {'enabled': false};
CREATE TABLE IF NOT EXISTS hopr.urls (short_key text PRIMARY KEY, long_url text, alias text, owner_id text, status text, created_at timestamp, expires_at timestamp);
