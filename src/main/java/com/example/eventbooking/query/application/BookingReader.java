package com.example.eventbooking.query.application;

import com.example.eventbooking.consistency.ReadSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingReader {

    Optional<BookingView> findBooking(UUID bookingId, ReadSource source);

    /** Newest first, one page. {@code eventId} is an optional filter. Not cached: it changes on every booking. */
    List<BookingView> listBookings(UUID eventId, int page, int size, ReadSource source);
}
