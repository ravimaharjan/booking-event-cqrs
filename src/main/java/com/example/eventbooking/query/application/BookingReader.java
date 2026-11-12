package com.example.eventbooking.query.application;

import com.example.eventbooking.consistency.ReadSource;
import java.util.Optional;
import java.util.UUID;

public interface BookingReader {

    Optional<BookingView> findBooking(UUID bookingId, ReadSource source);
}
