package com.example.eventbooking.query.application;

import com.example.eventbooking.consistency.ReadSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for events. A caching decorator can later be layered on this interface without touching
 * controllers or the JDBC implementation.
 */
public interface EventReader {

    List<EventSummary> listByCity(String city, int page, int size, ReadSource source);

    Optional<EventDetail> findEvent(UUID eventId, ReadSource source);
}
