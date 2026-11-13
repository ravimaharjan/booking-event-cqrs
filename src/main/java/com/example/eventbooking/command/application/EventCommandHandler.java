package com.example.eventbooking.command.application;

import com.example.eventbooking.command.domain.EventCreated;
import com.example.eventbooking.command.infrastructure.EventWriteRepository;
import com.example.eventbooking.outbox.OutboxWriter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(EventCommandHandler.class);

    private final EventWriteRepository events;
    private final ApplicationEventPublisher publisher;
    private final OutboxWriter outbox;

    EventCommandHandler(EventWriteRepository events, ApplicationEventPublisher publisher, OutboxWriter outbox) {
        this.events = events;
        this.publisher = publisher;
        this.outbox = outbox;
    }

    @Transactional
    public UUID handle(CreateEventCommand command) {
        UUID eventId = UUID.randomUUID();
        events.insertEvent(eventId, command.name(), command.city(), command.startsAt());
        command.tiers().forEach(t ->
                events.insertTier(UUID.randomUUID(), eventId, t.name(), t.priceCents(), t.quantity()));

        // Learning example of an event-driven design. The handler no longer knows that a denormalized
        // read model exists. It only announces the fact, and any interested part of the system reacts
        // (today: EventListingProjector). New reactions can be added without editing this class.
        //
        // The publish is synchronous: listeners run on this thread, inside this transaction. So the
        // write model and the read model still commit atomically and still replicate together.
        // That is deliberate. Making the listener asynchronous would make the read model eventually
        // consistent and break the read-after-write guarantee this project demonstrates.
        publisher.publishEvent(new EventCreated(eventId));
        // Same transaction: Debezium turns this into a Kafka message that invalidates cached listings.
        outbox.eventChanged("EventCreated", eventId);
        log.info("Event created: id={} city={} tiers={}", eventId, command.city(), command.tiers().size());
        return eventId;
    }
}
