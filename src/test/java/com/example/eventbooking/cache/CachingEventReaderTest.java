package com.example.eventbooking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.query.application.EventDetail;
import com.example.eventbooking.query.application.EventReader;
import com.example.eventbooking.query.application.EventSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CachingEventReaderTest {

    private final EventReader delegate = mock(EventReader.class);
    private final InMemoryReadCache cache = new InMemoryReadCache();
    private final CachingEventReader reader = new CachingEventReader(delegate,
            new CacheLoader(cache, new LocalCache(InMemoryReadCache.PROPERTIES), InMemoryReadCache.PROPERTIES), cache, InMemoryReadCache.PROPERTIES);

    private final UUID id = UUID.randomUUID();
    private final EventDetail detail = new EventDetail(id, "Concert", "Berlin", Instant.parse("2027-01-01T20:00:00Z"),
            List.of());

    @Test
    void primaryReadsBypassRedisEntirely() {
        when(delegate.findEvent(id, ReadSource.PRIMARY)).thenReturn(Optional.of(detail));

        assertThat(reader.findEvent(id, ReadSource.PRIMARY)).contains(detail);

        assertThat(cache.entries).isEmpty();
        assertThat(cache.lockAttempts).hasValue(0);
    }

    @Test
    void replicaMissReadsDatabaseAndFillsCache() {
        when(delegate.findEvent(id, ReadSource.REPLICA)).thenReturn(Optional.of(detail));

        assertThat(reader.findEvent(id, ReadSource.REPLICA)).contains(detail);

        assertThat(cache.entries.get("v1:event:" + id).value()).isEqualTo(detail);
        assertThat(cache.ttls).containsEntry("v1:event:" + id, Duration.ofSeconds(60));
    }

    @Test
    void replicaHitSkipsDatabase() {
        cache.put("v1:event:" + id, detail, Duration.ofSeconds(60));

        assertThat(reader.findEvent(id, ReadSource.REPLICA)).contains(detail);

        verifyNoInteractions(delegate);
    }

    @Test
    void notFoundIsNeverCached() {
        when(delegate.findEvent(id, ReadSource.REPLICA)).thenReturn(Optional.empty());

        assertThat(reader.findEvent(id, ReadSource.REPLICA)).isEmpty();

        assertThat(cache.entries).isEmpty();
    }

    @Test
    void emptyListingIsNeverCached() {
        when(delegate.listByCity("Berlin", 0, 20, ReadSource.REPLICA)).thenReturn(List.of());

        assertThat(reader.listByCity("Berlin", 0, 20, ReadSource.REPLICA)).isEmpty();

        assertThat(cache.entries).isEmpty();
    }

    @Test
    void listingKeyIncludesTheCityVersionSoABumpRetiresOldPages() {
        EventSummary s = new EventSummary(id, "Concert", "Berlin", Instant.parse("2027-01-01T20:00:00Z"), 5000, 10);
        cache.versions.put("v1:events:list-ver:c:Berlin", 7L);
        when(delegate.listByCity("Berlin", 0, 20, ReadSource.REPLICA)).thenReturn(List.of(s));

        reader.listByCity("Berlin", 0, 20, ReadSource.REPLICA);

        assertThat(cache.entries.get("v1:events:list:c:Berlin:7:0:20").value()).isEqualTo(List.of(s));
        assertThat(cache.ttls).containsEntry("v1:events:list:c:Berlin:7:0:20", Duration.ofSeconds(30));
    }
}
