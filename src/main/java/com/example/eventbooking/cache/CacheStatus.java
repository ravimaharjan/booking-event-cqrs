package com.example.eventbooking.cache;

import java.util.Locale;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Lets the cache decorators tell the controller how this request was answered, for the response headers. */
public final class CacheStatus {

    /** Response header describing what the cache did for this request. */
    public static final String HEADER = "X-Cache";

    /** What the cache did. {@link #BYPASS} means it was not consulted (primary read, or cache disabled). */
    public enum State {
        /** Fresh entry served from Redis. */
        HIT,
        /** Soft-expired entry served from Redis while one request refreshes it in the background. */
        STALE,
        /** No entry. This request rebuilt it from the replica. */
        MISS,
        /** No entry. This request did not touch the database: it waited for another request's rebuild. */
        COALESCED,
        BYPASS;

        public String headerValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String ATTRIBUTE = CacheStatus.class.getName() + ".STATE";

    private CacheStatus() {
    }

    static void mark(State state) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, state, RequestAttributes.SCOPE_REQUEST);
        }
    }

    public static State state() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object state = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return state instanceof State s ? s : State.BYPASS;
    }

    /** True when the response body came straight out of Redis. */
    public static boolean wasHit() {
        State state = state();
        return state == State.HIT || state == State.STALE;
    }
}
