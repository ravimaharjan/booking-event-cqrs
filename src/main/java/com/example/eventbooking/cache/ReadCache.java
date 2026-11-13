package com.example.eventbooking.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Thin Redis wrapper. Two failure policies:
 * <ul>
 *   <li>Read side ({@link #get}, {@link #put}, {@link #version}): Redis problems are logged and treated as a
 *       miss, so a Redis outage never fails a request, it only costs a database read.</li>
 *   <li>Rebuild locks ({@link #tryLock}): fail open. If Redis cannot be asked, the caller is told it may
 *       rebuild, so an outage costs database reads, never availability.</li>
 *   <li>Invalidation side ({@link #delete}, {@link #bump}): failures propagate so the Kafka consumer retries.
 *       Swallowing them would acknowledge the message while the stale entry stays.</li>
 * </ul>
 */
@Component
public class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);
    private static final Duration VERSION_TTL = Duration.ofDays(1);
    private static final String LOCK_PREFIX = "v1:lock:";
    /** Releases a lock only if we still own it, so a slow rebuilder cannot free someone else's lock. */
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;

    ReadCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * A cached value plus its soft expiry. After {@code softUntilMillis} the value is still usable but one
     * caller should refresh it. The hard expiry is the Redis TTL of the key itself.
     */
    public record CacheEntry<T>(T value, long softUntilMillis) {
        boolean softExpired(long nowMillis) {
            return nowMillis >= softUntilMillis;
        }
    }

    /** Reads an entry written by {@link #put}. Anything else under the key (old format, bad JSON) counts as a miss. */
    public <T> Optional<CacheEntry<T>> getEntry(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(json);
            if (!node.has("softUntil") || !node.has("value")) {
                return Optional.empty();
            }
            T value = mapper.readValue(mapper.treeAsTokens(node.get("value")), type);
            return Optional.of(new CacheEntry<>(value, node.get("softUntil").asLong()));
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Cache read failed for {}, treating as miss: {}", key, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Stores the value with a hard TTL of {@code ttl} plus or minus 10% jitter, so keys filled together do not
     * expire together. The soft expiry is a fraction of that hard TTL ({@code app.cache.soft-ttl-ratio}).
     */
    public void put(String key, Object value, Duration ttl) {
        try {
            long millis = ttl.toMillis();
            long jitter = ThreadLocalRandom.current().nextLong(-millis / 10, millis / 10 + 1);
            long hardMillis = millis + jitter;
            long softUntil = System.currentTimeMillis() + (long) (hardMillis * properties.softTtlRatio());
            String json = mapper.writeValueAsString(new Envelope(softUntil, value));
            redis.opsForValue().set(key, json, Duration.ofMillis(hardMillis));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Cache write failed for {}: {}", key, e.toString());
        }
    }

    private record Envelope(long softUntil, Object value) {
    }

    /**
     * Tries to become the single rebuilder of {@code key} across all app instances.
     *
     * @return a token to hand to {@link #unlock}, or {@code null} if another instance holds the lock
     */
    public String tryLock(String key, Duration lockTtl) {
        String token = UUID.randomUUID().toString();
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + key, token, lockTtl))
                    ? token : null;
        } catch (RuntimeException e) {
            log.warn("Rebuild lock unavailable for {}, rebuilding without it: {}", key, e.toString());
            return token;
        }
    }

    public void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK, List.of(LOCK_PREFIX + key), token);
        } catch (RuntimeException e) {
            log.warn("Rebuild unlock failed for {}, the lock expires by itself: {}", key, e.toString());
        }
    }

    /** Current version of a key family, 0 if none. Used to invalidate many keys with one increment. */
    public long version(String versionKey) {
        try {
            String v = redis.opsForValue().get(versionKey);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            log.warn("Cache version read failed for {}: {}", versionKey, e.toString());
            return 0;
        }
    }

    public void delete(String... keys) {
        redis.delete(Arrays.asList(keys));
    }

    /** Increments a version key. Entries written under the old version are never read again and expire by TTL. */
    public void bump(String versionKey) {
        redis.opsForValue().increment(versionKey);
        redis.expire(versionKey, VERSION_TTL);
    }
}
