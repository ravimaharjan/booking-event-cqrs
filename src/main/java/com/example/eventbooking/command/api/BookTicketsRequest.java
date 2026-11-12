package com.example.eventbooking.command.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

record BookTicketsRequest(
        @NotNull UUID tierId,
        @Positive int quantity,
        @NotBlank @Email String customerEmail) {
}
