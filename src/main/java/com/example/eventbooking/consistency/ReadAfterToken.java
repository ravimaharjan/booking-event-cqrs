package com.example.eventbooking.consistency;

import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Issues the consistency token returned by every command. Clients echo it back in the
 * {@value #HEADER} header on subsequent reads and treat it as opaque.
 *
 * <ul>
 *   <li>{@code lsn} mode: the primary's WAL position right after the commit (e.g. {@code 0/16B3748}).</li>
 *   <li>{@code time} mode: the epoch-millis commit time.</li>
 * </ul>
 */
@Component
public class ReadAfterToken {

    public static final String HEADER = "X-Read-After";
    public static final String SERVED_BY_HEADER = "X-Served-By";

    private final Clock clock;
    private final ConsistencyProperties properties;
    private final ReplicationPositions positions;

    ReadAfterToken(Clock clock, ConsistencyProperties properties, ReplicationPositions positions) {
        this.clock = clock;
        this.properties = properties;
        this.positions = positions;
    }

    /** Call after the command's transaction has committed. */
    public String issue() {
        if (properties.mode() == ConsistencyProperties.Mode.TIME) {
            return Long.toString(clock.millis());
        }
        return positions.primaryPosition();
    }
}
