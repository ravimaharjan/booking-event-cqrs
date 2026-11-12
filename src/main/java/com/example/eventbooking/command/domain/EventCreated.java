package com.example.eventbooking.command.domain;

import java.util.UUID;

/**
 * Domain event: an event (and its ticket tiers) has been written to the write model.
 *
 * <p>It carries only the id. Listeners load whatever they need from the database inside the same
 * transaction, which avoids duplicating write-model state in the event payload.
 */
public record EventCreated(UUID eventId) {
}
