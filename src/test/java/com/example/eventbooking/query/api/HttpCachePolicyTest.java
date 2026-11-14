package com.example.eventbooking.query.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.eventbooking.consistency.ReadSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpCachePolicyTest {

    private static HttpCachePolicy policy(boolean enabled, long sMaxAge, long swr) {
        return new HttpCachePolicy(new HttpCacheProperties(enabled, Duration.ofSeconds(sMaxAge), Duration.ofSeconds(swr)));
    }

    @Test
    void anonymousReadIsCacheableByASharedCacheForOneSecond() {
        assertThat(policy(true, 1, 5).header(ReadSource.REPLICA, true))
                .isEqualTo("public, max-age=0, s-maxage=1, stale-while-revalidate=5");
    }

    @Test
    void staleWhileRevalidateIsOmittedWhenZero() {
        assertThat(policy(true, 2, 0).header(ReadSource.REPLICA, true)).isEqualTo("public, max-age=0, s-maxage=2");
    }

    @Test
    void primaryReadsAreNeverCached() {
        assertThat(policy(true, 1, 5).header(ReadSource.PRIMARY, true)).isEqualTo("no-store");
    }

    @Test
    void notFoundIsNeverCached() {
        assertThat(policy(true, 1, 5).header(ReadSource.REPLICA, false)).isEqualTo("no-store");
    }

    @Test
    void disabledOrZeroLifetimeMeansNoStore() {
        assertThat(policy(false, 1, 5).header(ReadSource.REPLICA, true)).isEqualTo("no-store");
        assertThat(policy(true, 0, 5).header(ReadSource.REPLICA, true)).isEqualTo("no-store");
    }
}
