package com.example.eventbooking.query.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventDetail(UUID eventId, String name, String city, Instant startsAt, List<Tier> tiers) {

    public record Tier(UUID tierId, String name, long priceCents, int total, int available) {
    }
}
