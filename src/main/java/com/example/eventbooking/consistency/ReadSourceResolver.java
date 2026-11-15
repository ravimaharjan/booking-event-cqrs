package com.example.eventbooking.consistency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a read must go to the primary (the replica may not have the caller's write yet)
 * or may use a replica.
 */
@Component
public class ReadSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReadSourceResolver.class);
    private static final Pattern LSN = Pattern.compile("[0-9A-Fa-f]{1,8}/[0-9A-Fa-f]{1,8}");
    private static final long POLL_INTERVAL_MS = 10;

    private final Clock clock;
    private final ConsistencyProperties properties;
    private final ReplicationPositions positions;

    ReadSourceResolver(Clock clock, ConsistencyProperties properties, ReplicationPositions positions) {
        this.clock = clock;
        this.properties = properties;
        this.positions = positions;
    }

    public ReadSource resolve(String readAfterToken) {
        if (readAfterToken == null || readAfterToken.isBlank()) {
            return ReadSource.REPLICA;
        }
        String token = readAfterToken.trim();
        return properties.mode() == ConsistencyProperties.Mode.TIME ? resolveByTime(token) : resolveByLsn(token);
    }

    private ReadSource resolveByTime(String token) {
        Instant writtenAt = parseMillis(token);
        Duration age = Duration.between(writtenAt, clock.instant());
        // A negative age (clock skew between nodes) is treated as "just written".
        ReadSource source = age.compareTo(properties.readAfterWindow()) < 0 ? ReadSource.PRIMARY : ReadSource.REPLICA;
        log.debug("Read-after token age={}ms window={} -> {}", age.toMillis(), properties.readAfterWindow(), source);
        return source;
    }

    private ReadSource resolveByLsn(String token) {
        if (!LSN.matcher(token).matches()) {
            throw new InvalidReadAfterTokenException(token);
        }
        try {
            long deadline = System.nanoTime() + properties.lsnWaitTimeout().toNanos();
            while (true) {
                if (positions.replicaHasReached(token)) {
                    log.debug("Replica reached LSN {} -> REPLICA", token);
                    return ReadSource.REPLICA;
                }
                if (System.nanoTime() >= deadline) {
                    log.debug("Replica behind LSN {} after {} -> PRIMARY", token, properties.lsnWaitTimeout());
                    return ReadSource.PRIMARY;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReadSource.PRIMARY;
        } catch (RuntimeException e) {
            // Replica unreachable or LSN check failed: the primary is always correct.
            log.warn("LSN check failed for token {}, falling back to PRIMARY: {}", token, e.toString());
            return ReadSource.PRIMARY;
        }
    }

    private static Instant parseMillis(String token) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(token));
        } catch (NumberFormatException e) {
            throw new InvalidReadAfterTokenException(token);
        }
    }
}
