package com.example.eventbooking.query.application;

import java.time.Instant;
import java.util.UUID;

public record BookingView(UUID bookingId, UUID eventId, UUID tierId, int quantity,
                          String customerEmail, Instant createdAt) {
}
