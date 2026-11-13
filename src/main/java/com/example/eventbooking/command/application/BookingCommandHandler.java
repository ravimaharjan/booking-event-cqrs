package com.example.eventbooking.command.application;

import com.example.eventbooking.command.domain.InsufficientInventoryException;
import com.example.eventbooking.command.domain.TierNotFoundException;
import com.example.eventbooking.command.infrastructure.BookingWriteRepository;
import com.example.eventbooking.command.infrastructure.EventListingProjector;
import com.example.eventbooking.outbox.OutboxWriter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingCommandHandler.class);

    private final BookingWriteRepository bookings;
    private final EventListingProjector listing;
    private final OutboxWriter outbox;

    BookingCommandHandler(BookingWriteRepository bookings, EventListingProjector listing, OutboxWriter outbox) {
        this.bookings = bookings;
        this.listing = listing;
        this.outbox = outbox;
    }

    @Transactional
    public UUID handle(BookTicketsCommand command) {
        if (!bookings.tryTakeTickets(command.eventId(), command.tierId(), command.quantity())) {
            int available = bookings.availableTickets(command.eventId(), command.tierId())
                    .orElseThrow(() -> new TierNotFoundException(command.eventId(), command.tierId()));
            log.warn("Booking rejected, not enough tickets: event={} tier={} requested={} available={}",
                    command.eventId(), command.tierId(), command.quantity(), available);
            throw new InsufficientInventoryException(command.tierId(), command.quantity(), available);
        }
        UUID bookingId = UUID.randomUUID();
        bookings.insertBooking(bookingId, command.eventId(), command.tierId(),
                command.quantity(), command.customerEmail());
        listing.onTicketsBooked(command.eventId(), command.quantity());
        // Availability changed, so cached event detail and listings are stale.
        outbox.eventChanged("TicketsBooked", command.eventId());
        log.info("Booking created: id={} event={} tier={} qty={}",
                bookingId, command.eventId(), command.tierId(), command.quantity());
        return bookingId;
    }
}
