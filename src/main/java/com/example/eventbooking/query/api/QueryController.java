package com.example.eventbooking.query.api;

import com.example.eventbooking.consistency.ReadAfterToken;
import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.consistency.ReadSourceResolver;
import com.example.eventbooking.query.application.BookingReader;
import com.example.eventbooking.query.application.EventReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read side. Served from a replica unless the caller presents a recent {@code X-Read-After} token,
 * in which case the primary is used so the caller sees their own writes. {@code X-Served-By} reports
 * which one answered.
 */
@RestController
@RequestMapping("/api/queries")
class QueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventReader events;
    private final BookingReader bookings;
    private final ReadSourceResolver resolver;

    QueryController(EventReader events, BookingReader bookings, ReadSourceResolver resolver) {
        this.events = events;
        this.bookings = bookings;
        this.resolver = resolver;
    }

    @GetMapping("/events")
    ResponseEntity<?> listEvents(@RequestParam(required = false) String city,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestHeader(name = ReadAfterToken.HEADER, required = false) String token) {
        ReadSource source = resolver.resolve(token);
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<?> body = events.listByCity(city, Math.max(0, page), boundedSize, source);
        return respond(Optional.of(body), source);
    }

    @GetMapping("/events/{eventId}")
    ResponseEntity<?> getEvent(@PathVariable UUID eventId,
                               @RequestHeader(name = ReadAfterToken.HEADER, required = false) String token) {
        ReadSource source = resolver.resolve(token);
        return respond(events.findEvent(eventId, source), source);
    }

    @GetMapping("/bookings/{bookingId}")
    ResponseEntity<?> getBooking(@PathVariable UUID bookingId,
                                 @RequestHeader(name = ReadAfterToken.HEADER, required = false) String token) {
        ReadSource source = resolver.resolve(token);
        return respond(bookings.findBooking(bookingId, source), source);
    }

    private static ResponseEntity<?> respond(Optional<?> body, ReadSource source) {
        ResponseEntity.BodyBuilder builder = body.isPresent() ? ResponseEntity.ok() : ResponseEntity.status(404);
        return builder.header(ReadAfterToken.SERVED_BY_HEADER, source.name().toLowerCase()).body(body.orElse(null));
    }
}
