package com.example.eventbooking.command.application;

import java.util.UUID;

public record BookTicketsCommand(UUID eventId, UUID tierId, int quantity, String customerEmail) {
}
