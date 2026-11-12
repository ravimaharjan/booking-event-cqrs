package com.example.eventbooking.command.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventWriteRepository {

    private final JdbcClient jdbc;

    EventWriteRepository(@Qualifier("primaryJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(UUID id, String name, String city, Instant startsAt) {
        jdbc.sql("insert into events (id, name, city, starts_at) values (:id, :name, :city, :startsAt)")
                .param("id", id)
                .param("name", name)
                .param("city", city)
                .param("startsAt", OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC))
                .update();
    }

    public void insertTier(UUID id, UUID eventId, String name, long priceCents, int quantity) {
        jdbc.sql("""
                        insert into ticket_tiers (id, event_id, name, price_cents, total, available)
                        values (:id, :eventId, :name, :price, :qty, :qty)""")
                .param("id", id)
                .param("eventId", eventId)
                .param("name", name)
                .param("price", priceCents)
                .param("qty", quantity)
                .update();
    }
}
