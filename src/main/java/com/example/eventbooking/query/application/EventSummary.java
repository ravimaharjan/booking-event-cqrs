package com.example.eventbooking.query.application;

import java.time.Instant;
import java.util.UUID;

public record EventSummary(UUID eventId, String name, String city, Instant startsAt,
                           long minPriceCents, int availableTickets) {
}
