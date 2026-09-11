-- The keyspace is the unit of replication in Scylla/Cassandra.
-- NetworkTopologyStrategy with RF 3 stores every row on three nodes, which is
-- what makes QUORUM (2 of 3) reads and writes both consistent and survivable:
-- one node can be down without losing the ability to reach a quorum.
--
-- IF NOT EXISTS keeps this migration idempotent. It has to be: the keyspace must
-- already exist before Flyway can write its own schema-history table into it, so
-- this same statement is also used as the migrator's connection-time bootstrap
-- (see db-migration/build.gradle.kts). Flyway then replays it as V1 and records
-- it, so the file stays the single source of truth for the keyspace definition.
--
-- tablets = false: Scylla 6+ enables tablet-based data distribution by default, and
-- tablets do not yet support counter columns — url_click_counts (V3) cannot be created
-- in a tablets keyspace. Counters need the classic vnode ring, so the whole keyspace
-- opts out. Revisit once Scylla supports counters with tablets.
CREATE KEYSPACE IF NOT EXISTS hopr
WITH replication = {'class': 'NetworkTopologyStrategy', 'replication_factor': 3}
AND tablets = {'enabled': false};
