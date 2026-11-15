package com.example.eventbooking.consistency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode            how read-your-writes is enforced: {@code lsn} (default) compares the token with the
 *                        replica's replayed WAL position; {@code time} pins reads to the primary for a fixed window.
 * @param readAfterWindow {@code time} mode only: how long after a write a client's reads are pinned to the
 *                        primary. Must exceed the worst replication lag you want to hide.
 * @param lsnWaitTimeout  {@code lsn} mode only: how long a read waits for the replica to reach the token's
 *                        LSN before falling back to the primary. Zero means fall back immediately.
 */
@ConfigurationProperties("app.consistency")
public record ConsistencyProperties(Mode mode, Duration readAfterWindow, Duration lsnWaitTimeout) {

    public enum Mode { LSN, TIME }

    public ConsistencyProperties {
        mode = mode == null ? Mode.LSN : mode;
        readAfterWindow = readAfterWindow == null ? Duration.ofSeconds(5) : readAfterWindow;
        lsnWaitTimeout = lsnWaitTimeout == null ? Duration.ofMillis(100) : lsnWaitTimeout;
    }
}
