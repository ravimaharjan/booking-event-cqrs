package com.example.eventbooking.cache;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis-free {@link ReadCache} for tests. Entries keep their soft expiry so tests can age them. */
class InMemoryReadCache extends ReadCache {

    static final CacheProperties PROPERTIES = new CacheProperties(true, Duration.ofSeconds(60),
            Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofSeconds(5), 0.8,
            Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ZERO, 0);

    final Map<String, CacheEntry<Object>> entries = new ConcurrentHashMap<>();
    final Map<String, Long> versions = new ConcurrentHashMap<>();
    final Map<String, String> locks = new ConcurrentHashMap<>();
    final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    final AtomicLong lockAttempts = new AtomicLong();

    InMemoryReadCache() {
        super(mock(StringRedisTemplate.class), new ObjectMapper(), PROPERTIES, new LocalCache(PROPERTIES));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<CacheEntry<T>> getEntry(String key, TypeReference<T> type) {
        return Optional.ofNullable((CacheEntry<T>) (CacheEntry<?>) entries.get(key));
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        ttls.put(key, ttl);
        entries.put(key, new CacheEntry<>(value, System.currentTimeMillis() + (long) (ttl.toMillis() * 0.8)));
    }

    /** Stores an entry whose soft expiry has already passed. */
    void putSoftExpired(String key, Object value) {
        entries.put(key, new CacheEntry<>(value, System.currentTimeMillis() - 1));
    }

    @Override
    public long version(String versionKey) {
        return versions.getOrDefault(versionKey, 0L);
    }

    @Override
    public String tryLock(String key, Duration lockTtl) {
        lockAttempts.incrementAndGet();
        return locks.putIfAbsent(key, "t") == null ? "t" : null;
    }

    @Override
    public void unlock(String key, String token) {
        locks.remove(key, token);
    }

    @Override
    public void delete(String... keys) {
        for (String key : keys) {
            entries.remove(key);
        }
    }

    @Override
    public void bump(String versionKey) {
        versions.merge(versionKey, 1L, Long::sum);
    }
}
