package com.example.eventbooking.command.application;

import java.time.Instant;
import java.util.List;

public record CreateEventCommand(String name, String city, Instant startsAt, List<Tier> tiers) {

    public record Tier(String name, long priceCents, int quantity) {
    }
}
