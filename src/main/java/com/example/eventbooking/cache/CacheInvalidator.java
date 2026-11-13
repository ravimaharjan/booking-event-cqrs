package com.example.eventbooking.cache;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Removes stale entries after an event changed. Idempotent, so Kafka redelivery is harmless.
 *
 * <p>The invalidation runs twice. The first run is immediate. The second runs after the read-after window,
 * when the replica has caught up. Between the two, a reader can miss, read a replica that is still behind,
 * and cache that old value. The second run removes it, which bounds staleness to about the replica lag
 * instead of a full TTL.
 */
@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class CacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

    private final ReadCache cache;
    private final CacheProperties properties;
    private final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-second-invalidation");
        t.setDaemon(true);
        return t;
    });

    CacheInvalidator(ReadCache cache, CacheProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    /** Throws if Redis is unavailable, so the Kafka consumer retries the message. */
    void invalidateEvent(UUID eventId, String city) {
        evict(eventId, city);
        delayed.schedule(() -> {
            try {
                evict(eventId, city);
            } catch (RuntimeException e) {
                log.warn("Second invalidation failed for event {}, entries expire by TTL: {}", eventId, e.toString());
            }
        }, properties.secondInvalidationDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void evict(UUID eventId, String city) {
        cache.delete(CacheKeys.event(eventId));
        // Listings are many keys (pages, sizes). One version bump retires all of them without SCAN.
        cache.bump(CacheKeys.listVersion(CacheKeys.ALL_CITIES));
        if (city != null) {
            cache.bump(CacheKeys.listVersion(CacheKeys.listScope(city)));
        }
        log.debug("Invalidated cache for event {} city {}", eventId, city);
    }

    @PreDestroy
    void shutdown() {
        delayed.shutdownNow();
    }
}
