package com.example.eventbooking.query.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser and proxy caching of public query responses.
 *
 * @param enabled              master switch. When off, every query response is {@code no-store}.
 * @param sharedMaxAge         how long a shared cache (proxy, CDN) may reuse a response without asking the app
 * @param staleWhileRevalidate how long past that it may serve the old copy while fetching a new one in the background
 */
@ConfigurationProperties("app.http-cache")
public record HttpCacheProperties(boolean enabled, Duration sharedMaxAge, Duration staleWhileRevalidate) {
}
