package com.example.eventbooking.cache;

import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.query.application.BookingReader;
import com.example.eventbooking.query.application.BookingView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Cache-aside for bookings. A booking never changes after creation, so it needs no invalidation, only a TTL. */
@Primary
@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class CachingBookingReader implements BookingReader {

    private static final TypeReference<BookingView> VIEW = new TypeReference<>() {
    };

    private final BookingReader delegate;
    private final CacheLoader loader;
    private final CacheProperties properties;

    CachingBookingReader(@Qualifier("jdbcBookingReader") BookingReader delegate, CacheLoader loader,
                         CacheProperties properties) {
        this.delegate = delegate;
        this.loader = loader;
        this.properties = properties;
    }

    @Override
    public Optional<BookingView> findBooking(UUID bookingId, ReadSource source) {
        if (source == ReadSource.PRIMARY) {
            return delegate.findBooking(bookingId, source);
        }
        return loader.getOrLoad(CacheKeys.booking(bookingId), VIEW, properties.bookingTtl(),
                () -> delegate.findBooking(bookingId, source));
    }

    /**
     * Deliberately not cached. Unlike an event list, a booking list is private per user, so a shared cache fits
     * badly, and it changes on every new booking, which would need versioned-key invalidation like the event lists.
     * Single bookings are cached by id (see {@link #findBooking}) because they never change.
     */
    @Override
    public List<BookingView> listBookings(UUID eventId, int page, int size, ReadSource source) {
        return delegate.listBookings(eventId, page, size, source);
    }
}
