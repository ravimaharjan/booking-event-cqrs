package com.example.eventbooking.consistency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a read must go to the primary (the caller recently wrote) or may use a replica.
 */
@Component
public class ReadSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReadSourceResolver.class);

    private final Clock clock;
    private final ConsistencyProperties properties;

    ReadSourceResolver(Clock clock, ConsistencyProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public ReadSource resolve(String readAfterToken) {
        if (readAfterToken == null || readAfterToken.isBlank()) {
            return ReadSource.REPLICA;
        }
        Instant writtenAt = parse(readAfterToken.trim());
        Duration age = Duration.between(writtenAt, clock.instant());
        // A negative age (clock skew between nodes) is treated as "just written".
        ReadSource source = age.compareTo(properties.readAfterWindow()) < 0 ? ReadSource.PRIMARY : ReadSource.REPLICA;
        log.debug("Read-after token age={}ms window={} -> {}", age.toMillis(), properties.readAfterWindow(), source);
        return source;
    }

    private static Instant parse(String token) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(token));
        } catch (NumberFormatException e) {
            throw new InvalidReadAfterTokenException(token);
        }
    }
}
