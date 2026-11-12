package com.example.eventbooking.command.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingWriteRepository {

    private final JdbcClient jdbc;

    BookingWriteRepository(@Qualifier("primaryJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically takes {@code quantity} tickets. The conditional update makes overselling impossible
     * without an explicit lock or read-modify-write cycle.
     *
     * @return true if the tickets were taken, false if the tier is missing or has too few left
     */
    public boolean tryTakeTickets(UUID eventId, UUID tierId, int quantity) {
        return jdbc.sql("""
                        update ticket_tiers set available = available - :qty
                        where id = :tierId and event_id = :eventId and available >= :qty""")
                .param("qty", quantity)
                .param("tierId", tierId)
                .param("eventId", eventId)
                .update() == 1;
    }

    /** Remaining tickets of a tier, empty if the tier does not belong to the event. */
    public Optional<Integer> availableTickets(UUID eventId, UUID tierId) {
        return jdbc.sql("select available from ticket_tiers where id = :tierId and event_id = :eventId")
                .param("tierId", tierId)
                .param("eventId", eventId)
                .query(Integer.class)
                .optional();
    }

    public void insertBooking(UUID id, UUID eventId, UUID tierId, int quantity, String customerEmail) {
        jdbc.sql("""
                        insert into bookings (id, event_id, tier_id, quantity, customer_email)
                        values (:id, :eventId, :tierId, :qty, :email)""")
                .param("id", id)
                .param("eventId", eventId)
                .param("tierId", tierId)
                .param("qty", quantity)
                .param("email", customerEmail)
                .update();
    }
}
