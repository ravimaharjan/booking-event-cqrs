package com.example.eventbooking.command.api;

import com.example.eventbooking.command.application.BookTicketsCommand;
import com.example.eventbooking.command.application.BookingCommandHandler;
import com.example.eventbooking.command.application.CreateEventCommand;
import com.example.eventbooking.command.application.EventCommandHandler;
import com.example.eventbooking.consistency.ReadAfterToken;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write side. Every response carries an {@code X-Read-After} token, issued after the commit, that the
 * client sends on follow-up reads to be routed to the primary while replicas may still be catching up.
 */
@RestController
@RequestMapping("/api/commands")
class CommandController {

    private final EventCommandHandler eventHandler;
    private final BookingCommandHandler bookingHandler;
    private final ReadAfterToken readAfterToken;

    CommandController(EventCommandHandler eventHandler, BookingCommandHandler bookingHandler,
                      ReadAfterToken readAfterToken) {
        this.eventHandler = eventHandler;
        this.bookingHandler = bookingHandler;
        this.readAfterToken = readAfterToken;
    }

    @PostMapping("/events")
    ResponseEntity<CommandResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        UUID id = eventHandler.handle(request.toCommand());
        return created("/api/queries/events/" + id, id);
    }

    @PostMapping("/events/{eventId}/bookings")
    ResponseEntity<CommandResponse> bookTickets(@PathVariable UUID eventId,
                                                @Valid @RequestBody BookTicketsRequest request) {
        UUID id = bookingHandler.handle(new BookTicketsCommand(
                eventId, request.tierId(), request.quantity(), request.customerEmail()));
        return created("/api/queries/bookings/" + id, id);
    }

    private ResponseEntity<CommandResponse> created(String location, UUID id) {
        return ResponseEntity.created(URI.create(location))
                .header(ReadAfterToken.HEADER, readAfterToken.issue())
                .body(new CommandResponse(id));
    }
}
