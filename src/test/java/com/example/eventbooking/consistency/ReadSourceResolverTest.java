package com.example.eventbooking.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.eventbooking.consistency.ConsistencyProperties.Mode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReadSourceResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Fake replica that reaches any LSN after {@code callsUntilCaughtUp} checks (-1 = never). */
    private static final class FakePositions implements ReplicationPositions {
        final int callsUntilCaughtUp;
        final AtomicInteger calls = new AtomicInteger();
        boolean failing;

        FakePositions(int callsUntilCaughtUp) {
            this.callsUntilCaughtUp = callsUntilCaughtUp;
        }

        @Override
        public String primaryPosition() {
            return "0/16B3748";
        }

        @Override
        public boolean replicaHasReached(String lsn) {
            if (failing) {
                throw new IllegalStateException("replica down");
            }
            int n = calls.incrementAndGet();
            return callsUntilCaughtUp >= 0 && n > callsUntilCaughtUp;
        }
    }

    private static ReadSourceResolver lsn(FakePositions positions, Duration wait) {
        return new ReadSourceResolver(CLOCK, new ConsistencyProperties(Mode.LSN, null, wait), positions);
    }

    private static ReadSourceResolver time() {
        return new ReadSourceResolver(CLOCK,
                new ConsistencyProperties(Mode.TIME, Duration.ofSeconds(5), null), new FakePositions(0));
    }

    // ---- shared ----

    @Test
    void noTokenReadsFromReplica() {
        assertThat(lsn(new FakePositions(0), Duration.ZERO).resolve(null)).isEqualTo(ReadSource.REPLICA);
        assertThat(time().resolve("  ")).isEqualTo(ReadSource.REPLICA);
    }

    // ---- lsn mode ----

    @Test
    void lsnReplicaCaughtUpReadsFromReplica() {
        assertThat(lsn(new FakePositions(0), Duration.ZERO).resolve("0/16B3748")).isEqualTo(ReadSource.REPLICA);
    }

    @Test
    void lsnReplicaBehindFallsBackToPrimary() {
        assertThat(lsn(new FakePositions(-1), Duration.ZERO).resolve("0/16B3748")).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void lsnWaitsForReplicaToCatchUp() {
        FakePositions positions = new FakePositions(2);
        assertThat(lsn(positions, Duration.ofSeconds(2)).resolve("0/16B3748")).isEqualTo(ReadSource.REPLICA);
        assertThat(positions.calls.get()).isEqualTo(3);
    }

    @Test
    void lsnReplicaFailureFallsBackToPrimary() {
        FakePositions positions = new FakePositions(0);
        positions.failing = true;
        assertThat(lsn(positions, Duration.ofMillis(50)).resolve("0/16B3748")).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void lsnMalformedTokenIsRejectedWithoutQueryingReplica() {
        FakePositions positions = new FakePositions(0);
        assertThatThrownBy(() -> lsn(positions, Duration.ZERO).resolve("1'; DROP TABLE x;--"))
                .isInstanceOf(InvalidReadAfterTokenException.class);
        assertThat(positions.calls.get()).isZero();
    }

    // ---- time mode ----

    @Test
    void timeRecentTokenReadsFromPrimary() {
        assertThat(time().resolve(token(Duration.ofSeconds(1)))).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void timeTokenOlderThanWindowReadsFromReplica() {
        assertThat(time().resolve(token(Duration.ofSeconds(5)))).isEqualTo(ReadSource.REPLICA);
        assertThat(time().resolve(token(Duration.ofMinutes(10)))).isEqualTo(ReadSource.REPLICA);
    }

    @Test
    void timeFutureTokenFromClockSkewReadsFromPrimary() {
        assertThat(time().resolve(token(Duration.ofSeconds(-3)))).isEqualTo(ReadSource.PRIMARY);
    }

    @Test
    void timeMalformedTokenIsRejected() {
        assertThatThrownBy(() -> time().resolve("not-a-number"))
                .isInstanceOf(InvalidReadAfterTokenException.class);
    }

    private static String token(Duration age) {
        return Long.toString(NOW.minus(age).toEpochMilli());
    }
}
