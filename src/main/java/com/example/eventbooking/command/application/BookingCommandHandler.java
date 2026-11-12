package com.example.eventbooking.command.application;

import com.example.eventbooking.command.domain.InsufficientInventoryException;
import com.example.eventbooking.command.domain.TierNotFoundException;
import com.example.eventbooking.command.infrastructure.BookingWriteRepository;
import com.example.eventbooking.command.infrastructure.EventListingProjector;
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

    BookingCommandHandler(BookingWriteRepository bookings, EventListingProjector listing) {
        this.bookings = bookings;
        this.listing = listing;
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
        log.info("Booking created: id={} event={} tier={} qty={}",
                bookingId, command.eventId(), command.tierId(), command.quantity());
        return bookingId;
    }
}
