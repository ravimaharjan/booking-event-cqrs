package com.example.eventbooking.config;

import com.example.eventbooking.command.domain.InsufficientInventoryException;
import com.example.eventbooking.command.domain.TierNotFoundException;
import com.example.eventbooking.consistency.InvalidReadAfterTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(TierNotFoundException.class)
    ProblemDetail notFound(TierNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    ProblemDetail conflict(InsufficientInventoryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidReadAfterTokenException.class)
    ProblemDetail badToken(InvalidReadAfterTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
