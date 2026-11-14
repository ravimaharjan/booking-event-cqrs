package com.example.eventbooking.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tiny in-process cache (L1) in front of Redis. A hot entry is served from this JVM's memory for
 * {@code app.cache.local-ttl}, so a hot key costs no Redis call and no JSON parsing for most requests.
 *
 * <p>Each instance has its own copy, so an invalidation reaches the other instances only when their copy expires.
 * That is why the lifetime is short (hundreds of milliseconds): it is the extra staleness this layer can add.
 */
@Component
public class LocalCache {

    private final Cache<String, Object> entries;

    LocalCache(CacheProperties properties) {
        this.entries = properties.localTtl().isZero() || properties.localMaxEntries() <= 0
                ? null
                : Caffeine.newBuilder()
                        .maximumSize(properties.localMaxEntries())
                        .expireAfterWrite(properties.localTtl())
                        .build();
    }

    Optional<Object> get(String key) {
        return entries == null ? Optional.empty() : Optional.ofNullable(entries.getIfPresent(key));
    }

    void put(String key, Object value) {
        if (entries != null) {
            entries.put(key, value);
        }
    }

    void invalidate(String key) {
        if (entries != null) {
            entries.invalidate(key);
        }
    }
}
