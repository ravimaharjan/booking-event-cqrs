package com.example.eventbooking.query.api;

import com.example.eventbooking.consistency.ReadSource;
import org.springframework.stereotype.Component;

/**
 * Chooses the {@code Cache-Control} header of a query response.
 *
 * <ul>
 *   <li>Anonymous reads (replica or Redis) are the same for everyone, so a shared cache may keep them for a short
 *       time: {@code public, max-age=0, s-maxage=N}. Browsers always revalidate ({@code max-age=0}), only the proxy
 *       or CDN reuses the response.</li>
 *   <li>Reads served by the primary are for a caller who just wrote and must see their own write: {@code no-store}.</li>
 *   <li>Anything that is not a 200 is {@code no-store}, so a 404 from a replica that is still catching up is never
 *       cached.</li>
 * </ul>
 */
@Component
class HttpCachePolicy {

    static final String NO_STORE = "no-store";

    private final HttpCacheProperties properties;

    HttpCachePolicy(HttpCacheProperties properties) {
        this.properties = properties;
    }

    String header(ReadSource source, boolean found) {
        if (!properties.enabled() || source == ReadSource.PRIMARY || !found
                || properties.sharedMaxAge().toSeconds() <= 0) {
            return NO_STORE;
        }
        StringBuilder value = new StringBuilder("public, max-age=0, s-maxage=")
                .append(properties.sharedMaxAge().toSeconds());
        if (properties.staleWhileRevalidate().toSeconds() > 0) {
            value.append(", stale-while-revalidate=").append(properties.staleWhileRevalidate().toSeconds());
        }
        return value.toString();
    }
}
