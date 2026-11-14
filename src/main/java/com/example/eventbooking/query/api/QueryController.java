package com.example.eventbooking.query.api;

import com.example.eventbooking.cache.CacheStatus;
import com.example.eventbooking.consistency.ReadAfterToken;
import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.consistency.ReadSourceResolver;
import com.example.eventbooking.query.application.BookingReader;
import com.example.eventbooking.query.application.EventReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
 * which one answered: {@code primary}, {@code replica} or {@code cache} (Redis, replica reads only).
 * {@code X-Cache} says what the cache did: {@code hit}, {@code stale}, {@code miss}, {@code coalesced} or
 * {@code bypass}.
 */
@RestController
@RequestMapping("/api/queries")
class QueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventReader events;
    private final BookingReader bookings;
    private final ReadSourceResolver resolver;
    private final HttpCachePolicy httpCache;

    QueryController(EventReader events, BookingReader bookings, ReadSourceResolver resolver,
                    HttpCachePolicy httpCache) {
        this.events = events;
        this.bookings = bookings;
        this.resolver = resolver;
        this.httpCache = httpCache;
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

    @GetMapping("/bookings")
    ResponseEntity<?> listBookings(@RequestParam(required = false) UUID eventId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestHeader(name = ReadAfterToken.HEADER, required = false) String token) {
        ReadSource source = resolver.resolve(token);
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<?> body = bookings.listBookings(eventId, Math.max(0, page), boundedSize, source);
        return respond(Optional.of(body), source);
    }

    @GetMapping("/bookings/{bookingId}")
    ResponseEntity<?> getBooking(@PathVariable UUID bookingId,
                                 @RequestHeader(name = ReadAfterToken.HEADER, required = false) String token) {
        ReadSource source = resolver.resolve(token);
        return respond(bookings.findBooking(bookingId, source), source);
    }

    private ResponseEntity<?> respond(Optional<?> body, ReadSource source) {
        ResponseEntity.BodyBuilder builder = body.isPresent() ? ResponseEntity.ok() : ResponseEntity.status(404);
        String servedBy = CacheStatus.wasHit() ? "cache" : source.name().toLowerCase();
        return builder.header(ReadAfterToken.SERVED_BY_HEADER, servedBy)
                .header(CacheStatus.HEADER, CacheStatus.state().headerValue())
                .header(HttpHeaders.CACHE_CONTROL, httpCache.header(source, body.isPresent()))
                .body(body.orElse(null));
    }
}
