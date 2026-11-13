package com.example.eventbooking.cache;

import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.query.application.EventDetail;
import com.example.eventbooking.query.application.EventReader;
import com.example.eventbooking.query.application.EventSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Cache-aside decorator over the JDBC reader.
 *
 * <ul>
 *   <li>{@link ReadSource#PRIMARY} (the caller recently wrote) bypasses Redis completely, neither reading nor
 *       filling it, so read-after-write still holds.</li>
 *   <li>Replica reads go through {@link CacheLoader}: Redis first, and on a miss or soft expiry the replica is
 *       read by one request only (see {@link CacheLoader}).</li>
 *   <li>Empty results are never cached. A replica that is still catching up would otherwise get its
 *       "not found" frozen in Redis for a whole TTL.</li>
 * </ul>
 * Writes never touch these entries. They are invalidated by {@link CacheInvalidationConsumer}.
 */
@Primary
@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class CachingEventReader implements EventReader {

    private static final TypeReference<List<EventSummary>> SUMMARIES = new TypeReference<>() {
    };
    private static final TypeReference<EventDetail> DETAIL = new TypeReference<>() {
    };

    private final EventReader delegate;
    private final CacheLoader loader;
    private final ReadCache cache;
    private final CacheProperties properties;

    CachingEventReader(@Qualifier("jdbcEventReader") EventReader delegate, CacheLoader loader, ReadCache cache,
                       CacheProperties properties) {
        this.delegate = delegate;
        this.loader = loader;
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public List<EventSummary> listByCity(String city, int page, int size, ReadSource source) {
        if (source == ReadSource.PRIMARY) {
            return delegate.listByCity(city, page, size, source);
        }
        String scope = CacheKeys.listScope(city);
        String key = CacheKeys.list(scope, cache.version(CacheKeys.listVersion(scope)), page, size);
        return loader.getOrLoad(key, SUMMARIES, properties.listTtl(), () -> {
            List<EventSummary> fresh = delegate.listByCity(city, page, size, source);
            return fresh.isEmpty() ? Optional.empty() : Optional.of(fresh);
        }).orElse(List.of());
    }

    @Override
    public Optional<EventDetail> findEvent(UUID eventId, ReadSource source) {
        if (source == ReadSource.PRIMARY) {
            return delegate.findEvent(eventId, source);
        }
        return loader.getOrLoad(CacheKeys.event(eventId), DETAIL, properties.eventTtl(),
                () -> delegate.findEvent(eventId, source));
    }
}
