package com.example.eventbooking.command.domain;

import java.util.UUID;

public class TierNotFoundException extends RuntimeException {

    public TierNotFoundException(UUID eventId, UUID tierId) {
        super("Ticket tier %s not found for event %s".formatted(tierId, eventId));
    }
}
