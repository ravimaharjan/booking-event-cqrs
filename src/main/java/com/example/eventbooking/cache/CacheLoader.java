package com.example.eventbooking.cache;

import com.example.eventbooking.cache.ReadCache.CacheEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cache-aside read with protection against expiry stampedes on hot keys. Two mechanisms work together:
 *
 * <ol>
 *   <li><b>Stale-while-revalidate</b> for the normal case. An entry has a soft expiry before its hard TTL.
 *       Once soft-expired it is still served, and exactly one caller refreshes it in the background. The key
 *       never goes cold, so nobody waits and nobody stampedes.</li>
 *   <li><b>Single-flight</b> for a key that is really gone (hard TTL passed, or invalidated by a write). One
 *       caller per JVM rebuilds it (in-process future), one JVM per cluster rebuilds it (Redis lock), and the
 *       rest wait for that result instead of querying the database.</li>
 * </ol>
 *
 * Whoever waits is bounded ({@code app.cache.rebuild-wait}); after that a caller loads the value itself, so a
 * dead rebuilder or Redis outage can never block requests.
 */
@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class CacheLoader {

    private static final Logger log = LoggerFactory.getLogger(CacheLoader.class);
    private static final long POLL_MILLIS = 20;

    private final ReadCache cache;
    private final CacheProperties properties;

    /** Keys being rebuilt from a missing entry in this JVM. Followers wait on the leader's future. */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<?>>> loading = new ConcurrentHashMap<>();
    /** Keys being refreshed in the background in this JVM. Only the first caller per key starts a refresh. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final ExecutorService refresher = Executors.newVirtualThreadPerTaskExecutor();

    CacheLoader(ReadCache cache, CacheProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    /**
     * @param loader reads the value from the database. Empty means "not found" and is never cached.
     */
    <T> Optional<T> getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<Optional<T>> loader) {
        Optional<CacheEntry<T>> entry = cache.getEntry(key, type);
        if (entry.isPresent()) {
            if (!entry.get().softExpired(System.currentTimeMillis())) {
                CacheStatus.mark(CacheStatus.State.HIT);
                return Optional.of(entry.get().value());
            }
            refreshInBackground(key, ttl, loader);
            CacheStatus.mark(CacheStatus.State.STALE);
            return Optional.of(entry.get().value());
        }
        return loadMissing(key, type, ttl, loader);
    }

    // ---- stale-while-revalidate -------------------------------------------------------------------------

    /** Never blocks the caller. At most one refresh per key per JVM, and one per cluster via the Redis lock. */
    private <T> void refreshInBackground(String key, Duration ttl, Supplier<Optional<T>> loader) {
        if (!refreshing.add(key)) {
            return;
        }
        try {
            refresher.execute(() -> {
                try {
                    String lock = cache.tryLock(key, properties.rebuildLockTtl());
                    if (lock == null) {
                        return; // another instance is already refreshing it
                    }
                    try {
                        loader.get().ifPresent(fresh -> cache.put(key, fresh, ttl));
                    } finally {
                        cache.unlock(key, lock);
                    }
                } catch (RuntimeException e) {
                    log.warn("Background refresh of {} failed, serving the old entry until it expires: {}",
                            key, e.toString());
                } finally {
                    refreshing.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            refreshing.remove(key);
        }
    }

    // ---- single-flight ----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> Optional<T> loadMissing(String key, TypeReference<T> type, Duration ttl,
                                        Supplier<Optional<T>> loader) {
        CompletableFuture<Optional<?>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<?>> leader = loading.putIfAbsent(key, mine);
        if (leader != null) {
            return awaitLeader(leader, key, loader);
        }
        try {
            Optional<T> result = loadAcrossInstances(key, type, ttl, loader);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, mine);
        }
    }

    /** Same-JVM follower: share the leader's result. Falls back to loading itself if the leader is too slow. */
    @SuppressWarnings("unchecked")
    private <T> Optional<T> awaitLeader(CompletableFuture<Optional<?>> leader, String key,
                                        Supplier<Optional<T>> loader) {
        try {
            Optional<T> result = (Optional<T>) leader.get(properties.rebuildWait().toMillis(), TimeUnit.MILLISECONDS);
            CacheStatus.mark(CacheStatus.State.COALESCED);
            return result;
        } catch (TimeoutException e) {
            log.debug("Rebuild of {} is slow, loading it directly", key);
            CacheStatus.mark(CacheStatus.State.MISS);
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the rebuild of " + key, e);
        } catch (ExecutionException e) {
            // The leader failed (for example the database is down). Fail the same way instead of sending every
            // follower to the database, which is what we are protecting.
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
    }

    /** The JVM's leader: win the cluster-wide lock and load, or wait for the instance that holds it. */
    private <T> Optional<T> loadAcrossInstances(String key, TypeReference<T> type, Duration ttl,
                                                Supplier<Optional<T>> loader) {
        String lock = cache.tryLock(key, properties.rebuildLockTtl());
        if (lock != null) {
            try {
                // Another instance may have finished between our miss and our lock.
                Optional<CacheEntry<T>> filled = cache.getEntry(key, type);
                if (filled.isPresent()) {
                    CacheStatus.mark(CacheStatus.State.COALESCED);
                    return Optional.of(filled.get().value());
                }
                return loadAndStore(key, ttl, loader);
            } finally {
                cache.unlock(key, lock);
            }
        }
        long deadline = System.nanoTime() + properties.rebuildWait().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<CacheEntry<T>> filled = cache.getEntry(key, type);
            if (filled.isPresent()) {
                CacheStatus.mark(CacheStatus.State.COALESCED);
                return Optional.of(filled.get().value());
            }
        }
        log.debug("Rebuild of {} by another instance took too long, loading it directly", key);
        return loadAndStore(key, ttl, loader);
    }

    private <T> Optional<T> loadAndStore(String key, Duration ttl, Supplier<Optional<T>> loader) {
        CacheStatus.mark(CacheStatus.State.MISS);
        Optional<T> fresh = loader.get();
        fresh.ifPresent(value -> cache.put(key, value, ttl));
        return fresh;
    }
}
