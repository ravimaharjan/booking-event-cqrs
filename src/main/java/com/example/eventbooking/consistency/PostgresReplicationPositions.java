package com.example.eventbooking.consistency;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class PostgresReplicationPositions implements ReplicationPositions {

    private final JdbcClient primary;
    private final JdbcClient replica;

    PostgresReplicationPositions(@Qualifier("primaryJdbcClient") JdbcClient primary,
                                 @Qualifier("replicaJdbcClient") JdbcClient replica) {
        this.primary = primary;
        this.replica = replica;
    }

    @Override
    public String primaryPosition() {
        return primary.sql("SELECT pg_current_wal_lsn()::text").query(String.class).single();
    }

    @Override
    public boolean replicaHasReached(String lsn) {
        // pg_last_wal_replay_lsn() is NULL if the server is not in recovery; treat that as "not reached".
        Boolean reached = replica.sql("SELECT pg_last_wal_replay_lsn() >= CAST(:lsn AS pg_lsn)")
                .param("lsn", lsn)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(reached);
    }
}
