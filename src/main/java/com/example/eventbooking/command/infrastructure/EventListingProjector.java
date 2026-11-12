package com.example.eventbooking.command.infrastructure;

import com.example.eventbooking.command.domain.EventCreated;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Maintains the denormalized {@code event_listing} read model. Always runs inside the command's
 * transaction so the write and read models commit, and replicate, together.
 */
@Component
public class EventListingProjector {

    private final JdbcClient jdbc;

    EventListingProjector(@Qualifier("primaryJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reacts to {@link EventCreated}. A plain {@code @EventListener} is used on purpose: it is invoked
     * synchronously on the publisher's thread, so it joins the command's transaction. If this insert
     * fails, the whole command rolls back and the two models can never diverge.
     *
     * <p>{@code @TransactionalEventListener} is avoided here because with its default settings a
     * listener is silently skipped when no transaction is active, and its AFTER_COMMIT phase would run
     * outside the transaction, which is the wrong guarantee for a read model we need atomically.
     */
    @EventListener
    public void on(EventCreated event) {
        jdbc.sql("""
                        insert into event_listing (event_id, name, city, starts_at, min_price_cents, available_tickets)
                        select e.id, e.name, e.city, e.starts_at, min(t.price_cents), sum(t.available)
                        from events e join ticket_tiers t on t.event_id = e.id
                        where e.id = :eventId
                        group by e.id""")
                .param("eventId", event.eventId())
                .update();
    }

    /** Still a direct call from {@code BookingCommandHandler}, kept as the contrast to the event-driven path. */
    public void onTicketsBooked(UUID eventId, int quantity) {
        jdbc.sql("""
                        update event_listing
                        set available_tickets = available_tickets - :qty, updated_at = now()
                        where event_id = :eventId""")
                .param("qty", quantity)
                .param("eventId", eventId)
                .update();
    }
}
