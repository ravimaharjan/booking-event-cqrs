package com.example.eventbooking.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReadSourceResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final ReadSourceResolver resolver = new ReadSourceResolver(
            Clock.fixed(NOW, ZoneOffset.UTC), new ConsistencyProperties(Duration.ofSeconds(5)));

    @Test
    void noTokenReadsFromReplica() {
        assertThat(resolver.resolve(null)).isEqualTo(ReadSource.REPLICA);
        assertThat(resolver.resolve("  ")).isEqualTo(ReadSource.REPLICA);
    }

    @Test
    void recentTokenReadsFromPrimary() {
        assertThat(resolver.resolve(token(Duration.ofSeconds(1)))).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void tokenOlderThanWindowReadsFromReplica() {
        assertThat(resolver.resolve(token(Duration.ofSeconds(5)))).isEqualTo(ReadSource.REPLICA);
        assertThat(resolver.resolve(token(Duration.ofMinutes(10)))).isEqualTo(ReadSource.REPLICA);
    }

    @Test
    void futureTokenFromClockSkewReadsFromPrimary() {
        assertThat(resolver.resolve(token(Duration.ofSeconds(-3)))).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("not-a-number"))
                .isInstanceOf(InvalidReadAfterTokenException.class);
    }

    private static String token(Duration age) {
        return Long.toString(NOW.minus(age).toEpochMilli());
    }
}
