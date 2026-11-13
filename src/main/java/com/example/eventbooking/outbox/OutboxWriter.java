package com.example.eventbooking.outbox;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes outbox rows. Must be called inside the command's transaction (MANDATORY), otherwise the row
 * could be committed without the business change, or the other way round.
 *
 * <p>The row is deleted right after the insert, in the same transaction. Debezium captures the insert
 * from the WAL, so the table stays empty and never grows. The delete is ignored by the event router.
 */
@Component
public class OutboxWriter {

    public static final String EVENT_AGGREGATE = "event";

    private final JdbcClient jdbc;

    OutboxWriter(@Qualifier("primaryJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Announces that an event, or data derived from it (listing, tiers, availability), changed. The
     * payload carries the event id and its city, which is all a cache invalidator needs. It is built in
     * SQL from the committed-in-this-transaction state of {@code events}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void eventChanged(String type, UUID eventId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into outbox (id, aggregate_type, aggregate_id, type, payload)
                        select :id, :aggregateType, cast(e.id as varchar), :type,
                               jsonb_build_object('type', cast(:type as varchar), 'eventId', e.id, 'city', e.city)
                        from events e where e.id = :eventId""")
                .param("id", id)
                .param("aggregateType", EVENT_AGGREGATE)
                .param("type", type)
                .param("eventId", eventId)
                .update();
        jdbc.sql("delete from outbox where id = :id").param("id", id).update();
    }
}
