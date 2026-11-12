package com.example.eventbooking.consistency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param readAfterWindow how long after a write a client's reads are pinned to the primary.
 *                        Must exceed the worst replication lag you want to hide.
 */
@ConfigurationProperties("app.consistency")
public record ConsistencyProperties(Duration readAfterWindow) {
}
