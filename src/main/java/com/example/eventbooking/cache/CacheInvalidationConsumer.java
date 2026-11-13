package com.example.eventbooking.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes outbox messages published by Debezium (topic {@code outbox.event}) and invalidates the cache.
 * Payload: {@code {"type": "...", "eventId": "...", "city": "..."}}. Message key is the event id, so all
 * messages for one event arrive in order.
 */
@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class CacheInvalidationConsumer {

    static final String TOPIC = "outbox.event";

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationConsumer.class);

    private final CacheInvalidator invalidator;
    private final ObjectMapper mapper;

    CacheInvalidationConsumer(CacheInvalidator invalidator, ObjectMapper mapper) {
        this.invalidator = invalidator;
        this.mapper = mapper;
    }

    @KafkaListener(topics = TOPIC, groupId = "cache-invalidator")
    void on(String message) {
        UUID eventId;
        String city;
        try {
            JsonNode json = mapper.readTree(message);
            eventId = UUID.fromString(json.path("eventId").asText());
            city = json.hasNonNull("city") ? json.get("city").asText() : null;
        } catch (Exception e) {
            // Malformed message: retrying cannot fix it, so skip it instead of blocking the partition.
            log.error("Skipping unparseable outbox message: {}", message, e);
            return;
        }
        log.info("Outbox message received: eventId={} city={}", eventId, city);
        invalidator.invalidateEvent(eventId, city);
    }
}
