package com.example.eventbooking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheLoaderTest {

    private static final TypeReference<String> STRING = new TypeReference<>() {
    };
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY = "v1:event:hot";

    private final InMemoryReadCache cache = new InMemoryReadCache();
    private final CacheLoader loader = new CacheLoader(cache, InMemoryReadCache.PROPERTIES);

    /** Runs {@code callers} concurrent reads of the hot key and returns every result. */
    private List<Optional<String>> hammer(int callers, java.util.function.Supplier<Optional<String>> db)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<String>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return loader.getOrLoad(KEY, STRING, TTL, db);
                }));
            }
            start.countDown();
            List<Optional<String>> results = new ArrayList<>();
            for (Future<Optional<String>> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @Test
    void tenThousandRequestsForAMissingKeyReadTheDatabaseOnce() throws Exception {
        AtomicInteger dbReads = new AtomicInteger();

        List<Optional<String>> results = hammer(10_000, () -> {
            dbReads.incrementAndGet();
            sleep(150);
            return Optional.of("fresh");
        });

        assertThat(dbReads).hasValue(1);
        assertThat(results).hasSize(10_000).allMatch(r -> r.equals(Optional.of("fresh")));
        assertThat(cache.entries).containsKey(KEY);
        assertThat(cache.locks).isEmpty();
    }

    @Test
    void softExpiredKeyIsServedImmediatelyAndRefreshedOnce() throws Exception {
        cache.putSoftExpired(KEY, "old");
        AtomicInteger dbReads = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseRefresh = new CompletableFuture<>();

        // The refresh is held open, so any caller that waited for it would hang the test.
        List<Optional<String>> results = hammer(10_000, () -> {
            dbReads.incrementAndGet();
            refreshStarted.countDown();
            releaseRefresh.join();
            return Optional.of("new");
        });

        assertThat(results).hasSize(10_000).allMatch(r -> r.equals(Optional.of("old")));
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseRefresh.complete(null);
        awaitValue("new");
        assertThat(dbReads).hasValue(1);
        assertThat(cache.locks).isEmpty();
    }

    @Test
    void freshKeyNeverTouchesTheDatabase() throws Exception {
        cache.put(KEY, "cached", TTL);
        AtomicInteger dbReads = new AtomicInteger();

        List<Optional<String>> results = hammer(1_000, () -> {
            dbReads.incrementAndGet();
            return Optional.of("db");
        });

        assertThat(dbReads).hasValue(0);
        assertThat(results).allMatch(r -> r.equals(Optional.of("cached")));
    }

    @Test
    void anotherInstanceHoldingTheLockIsWaitedFor() {
        cache.locks.put(KEY, "other-instance");
        AtomicInteger dbReads = new AtomicInteger();
        CompletableFuture.runAsync(() -> {
            sleep(100);
            cache.put(KEY, "from-other-instance", TTL);
        });

        Optional<String> result = loader.getOrLoad(KEY, STRING, TTL, () -> {
            dbReads.incrementAndGet();
            return Optional.of("db");
        });

        assertThat(result).contains("from-other-instance");
        assertThat(dbReads).hasValue(0);
    }

    @Test
    void waitingForADeadRebuilderGivesUpAndLoadsDirectly() {
        cache.locks.put(KEY, "dead-instance");

        Optional<String> result = loader.getOrLoad(KEY, STRING, TTL, () -> Optional.of("db"));

        assertThat(result).contains("db");
        assertThat(cache.entries).containsKey(KEY);
    }

    @Test
    void notFoundIsNotCachedAndFollowersGetTheSameAnswer() throws Exception {
        AtomicInteger dbReads = new AtomicInteger();

        List<Optional<String>> results = hammer(500, () -> {
            dbReads.incrementAndGet();
            sleep(100);
            return Optional.empty();
        });

        assertThat(dbReads).hasValue(1);
        assertThat(results).allMatch(Optional::isEmpty);
        assertThat(cache.entries).isEmpty();
    }

    @Test
    void failedRebuildFailsFollowersToo_andTheNextRequestRetries() throws Exception {
        AtomicInteger dbReads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return loader.getOrLoad(KEY, STRING, TTL, () -> {
                        dbReads.incrementAndGet();
                        sleep(100);
                        throw new IllegalStateException("db down");
                    });
                }));
            }
            start.countDown();
            for (Future<Optional<String>> f : futures) {
                assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("db down");
            }
        }
        assertThat(dbReads).hasValue(1);
        assertThat(cache.locks).isEmpty();

        assertThat(loader.getOrLoad(KEY, STRING, TTL, () -> Optional.of("recovered"))).contains("recovered");
    }

    @Test
    void failedBackgroundRefreshKeepsServingTheOldEntry() throws Exception {
        cache.putSoftExpired(KEY, "old");
        CountDownLatch attempted = new CountDownLatch(1);

        Optional<String> result = loader.getOrLoad(KEY, STRING, TTL, () -> {
            attempted.countDown();
            throw new IllegalStateException("db down");
        });

        assertThat(result).contains("old");
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitLockReleased();
        assertThat(cache.getEntry(KEY, STRING)).get().extracting(ReadCache.CacheEntry::value).isEqualTo("old");
        // A later request starts a new refresh (the previous attempt may still be finishing, so keep asking).
        for (int i = 0; i < 100 && !cache.getEntry(KEY, STRING).get().value().equals("new"); i++) {
            assertThat(loader.getOrLoad(KEY, STRING, TTL, () -> Optional.of("new"))).isPresent();
            Thread.sleep(25);
        }
        awaitValue("new");
    }

    private void awaitValue(String expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<ReadCache.CacheEntry<String>> e = cache.getEntry(KEY, STRING);
            if (e.isPresent() && e.get().value().equals(expected) && cache.locks.isEmpty()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("cache never reached value " + expected);
    }

    private void awaitLockReleased() throws InterruptedException {
        for (int i = 0; i < 200 && !cache.locks.isEmpty(); i++) {
            Thread.sleep(25);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
