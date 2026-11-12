package com.example.eventbooking.command.api;

import com.example.eventbooking.command.application.CreateEventCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

record CreateEventRequest(
        @NotBlank String name,
        @NotBlank String city,
        @NotNull Instant startsAt,
        @NotEmpty List<@Valid @NotNull TierRequest> tiers) {

    record TierRequest(@NotBlank String name, @PositiveOrZero long priceCents, @Positive int quantity) {
    }

    CreateEventCommand toCommand() {
        return new CreateEventCommand(name, city, startsAt, tiers.stream()
                .map(t -> new CreateEventCommand.Tier(t.name(), t.priceCents(), t.quantity()))
                .toList());
    }
}
