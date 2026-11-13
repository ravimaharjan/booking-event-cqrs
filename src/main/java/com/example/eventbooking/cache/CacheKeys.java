package com.example.eventbooking.cache;

import java.util.UUID;

/** Key layout. The {@code v1} prefix lets a deploy that changes a DTO shape start with a clean keyspace. */
final class CacheKeys {

    static final String ALL_CITIES = "_all";

    private CacheKeys() {
    }

    static String event(UUID eventId) {
        return "v1:event:" + eventId;
    }

    static String booking(UUID bookingId) {
        return "v1:booking:" + bookingId;
    }

    /** Scope of a listing: one city, or every city when the filter is absent. */
    static String listScope(String city) {
        return city == null ? ALL_CITIES : "c:" + city;
    }

    static String listVersion(String scope) {
        return "v1:events:list-ver:" + scope;
    }

    static String list(String scope, long version, int page, int size) {
        return "v1:events:list:" + scope + ":" + version + ":" + page + ":" + size;
    }
}
