package com.example.eventbooking.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalCacheTest {

    private static final TypeReference<String> STRING = new TypeReference<>() {
    };
    private static final Duration TTL = Duration.ofSeconds(60);

    private static CacheProperties withLocal(Duration localTtl, long max) {
        CacheProperties base = InMemoryReadCache.PROPERTIES;
        return new CacheProperties(true, base.eventTtl(), base.listTtl(), base.bookingTtl(),
                base.secondInvalidationDelay(), base.softTtlRatio(), base.rebuildLockTtl(), base.rebuildWait(),
                localTtl, max);
    }

    @Test
    void secondReadIsServedFromMemoryWithoutRedisOrDatabase() {
        CacheProperties props = withLocal(Duration.ofSeconds(30), 100);
        InMemoryReadCache redis = new InMemoryReadCache();
        CacheLoader loader = new CacheLoader(redis, new LocalCache(props), props);
        AtomicInteger dbReads = new AtomicInteger();

        loader.getOrLoad("k", STRING, TTL, () -> {
            dbReads.incrementAndGet();
            return Optional.of("v");
        });
        redis.entries.clear(); // Redis forgets it; L1 must still answer
        Optional<String> second = loader.getOrLoad("k", STRING, TTL, () -> {
            dbReads.incrementAndGet();
            return Optional.of("db-again");
        });

        assertThat(second).contains("v");
        assertThat(dbReads).hasValue(1);
    }

    @Test
    void invalidatedKeyIsReadAgain() {
        CacheProperties props = withLocal(Duration.ofSeconds(30), 100);
        LocalCache local = new LocalCache(props);
        InMemoryReadCache redis = new InMemoryReadCache();
        CacheLoader loader = new CacheLoader(redis, local, props);
        loader.getOrLoad("k", STRING, TTL, () -> Optional.of("old"));

        redis.entries.clear();
        local.invalidate("k");

        assertThat(loader.getOrLoad("k", STRING, TTL, () -> Optional.of("new"))).contains("new");
    }

    @Test
    void entryExpiresAfterTheLocalTtl() throws InterruptedException {
        CacheProperties props = withLocal(Duration.ofMillis(50), 100);
        InMemoryReadCache redis = new InMemoryReadCache();
        CacheLoader loader = new CacheLoader(redis, new LocalCache(props), props);
        loader.getOrLoad("k", STRING, TTL, () -> Optional.of("old"));

        redis.entries.clear();
        Thread.sleep(150);

        assertThat(loader.getOrLoad("k", STRING, TTL, () -> Optional.of("new"))).contains("new");
    }

    @Test
    void zeroTtlDisablesTheLocalCache() {
        LocalCache local = new LocalCache(withLocal(Duration.ZERO, 100));
        local.put("k", "v");
        assertThat(local.get("k")).isEmpty();
    }

    @Test
    void notFoundIsNotKeptInMemory() {
        CacheProperties props = withLocal(Duration.ofSeconds(30), 100);
        InMemoryReadCache redis = new InMemoryReadCache();
        CacheLoader loader = new CacheLoader(redis, new LocalCache(props), props);
        loader.getOrLoad("k", STRING, TTL, Optional::empty);

        assertThat(loader.getOrLoad("k", STRING, TTL, () -> Optional.of("now-exists"))).contains("now-exists");
    }
}
