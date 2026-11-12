package com.example.eventbooking.command.domain;

import java.util.UUID;

public class InsufficientInventoryException extends RuntimeException {

    public InsufficientInventoryException(UUID tierId, int requested, int available) {
        super("Tier %s has %d tickets available, %d requested".formatted(tierId, available, requested));
    }
}
