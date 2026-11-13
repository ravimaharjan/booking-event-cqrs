package com.example.eventbooking.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled                   master switch for the Redis read cache and the Kafka invalidation consumer
 * @param eventTtl                  event detail entries. Availability changes on every booking, so keep short.
 * @param listTtl                   city listing pages
 * @param bookingTtl                bookings are immutable once created, so a long TTL is safe
 * @param secondInvalidationDelay   delay of the second invalidation. Set it to the read-after window: by then
 *                                  the replica has caught up, so a stale value cached from it is removed.
 * @param softTtlRatio              fraction of the hard TTL after which an entry is "soft expired": still served,
 *                                  but one request refreshes it in the background (stale-while-revalidate)
 * @param rebuildLockTtl            safety expiry of the cross-instance rebuild lock, in case its owner dies
 * @param rebuildWait               how long a request waits for another request's rebuild of a missing key before
 *                                  loading it itself
 */
@ConfigurationProperties("app.cache")
public record CacheProperties(boolean enabled, Duration eventTtl, Duration listTtl, Duration bookingTtl,
                              Duration secondInvalidationDelay, double softTtlRatio,
                              Duration rebuildLockTtl, Duration rebuildWait) {
}
