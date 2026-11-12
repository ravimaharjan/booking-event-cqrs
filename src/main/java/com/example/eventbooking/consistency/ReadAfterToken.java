package com.example.eventbooking.consistency;

import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Issues the consistency token returned by every command. The token is the epoch-millis time at which
 * the write was committed. Clients echo it back in the {@value #HEADER} header on subsequent reads.
 *
 * <p>The token is an opaque string to clients. To move to LSN-based routing later, only this class and
 * {@link ReadSourceResolver} change.
 */
@Component
public class ReadAfterToken {

    public static final String HEADER = "X-Read-After";
    public static final String SERVED_BY_HEADER = "X-Served-By";

    private final Clock clock;

    ReadAfterToken(Clock clock) {
        this.clock = clock;
    }

    /** Call after the command's transaction has committed. */
    public String issue() {
        return Long.toString(clock.millis());
    }
}
