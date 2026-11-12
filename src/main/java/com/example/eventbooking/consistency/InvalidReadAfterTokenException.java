package com.example.eventbooking.consistency;

public class InvalidReadAfterTokenException extends RuntimeException {

    public InvalidReadAfterTokenException(String token) {
        super("Invalid " + ReadAfterToken.HEADER + " token: " + token);
    }
}
