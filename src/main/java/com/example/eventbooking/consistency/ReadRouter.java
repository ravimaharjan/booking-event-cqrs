package com.example.eventbooking.consistency;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Maps a {@link ReadSource} to the connection pool that serves it. */
@Component
public class ReadRouter {

    private final JdbcClient primary;
    private final JdbcClient replica;

    ReadRouter(@Qualifier("primaryJdbcClient") JdbcClient primary,
               @Qualifier("replicaJdbcClient") JdbcClient replica) {
        this.primary = primary;
        this.replica = replica;
    }

    public JdbcClient forSource(ReadSource source) {
        return source == ReadSource.PRIMARY ? primary : replica;
    }
}
