package com.example.eventbooking.query.infrastructure;

import com.example.eventbooking.consistency.ReadRouter;
import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.query.application.EventDetail;
import com.example.eventbooking.query.application.EventReader;
import com.example.eventbooking.query.application.EventSummary;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEventReader implements EventReader {

    private final ReadRouter router;

    JdbcEventReader(ReadRouter router) {
        this.router = router;
    }

    @Override
    public List<EventSummary> listByCity(String city, int page, int size, ReadSource source) {
        return router.forSource(source).sql("""
                        select event_id, name, city, starts_at, min_price_cents, available_tickets
                        from event_listing
                        where (cast(:city as varchar) is null or city = :city)
                        order by starts_at, event_id
                        limit :limit offset :offset""")
                .param("city", city)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, i) -> new EventSummary(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("min_price_cents"),
                        rs.getInt("available_tickets")))
                .list();
    }

    @Override
    public Optional<EventDetail> findEvent(UUID eventId, ReadSource source) {
        record Row(UUID eventId, String name, String city, java.time.Instant startsAt, EventDetail.Tier tier) {
        }
        List<Row> rows = router.forSource(source).sql("""
                        select e.id, e.name, e.city, e.starts_at,
                               t.id as tier_id, t.name as tier_name, t.price_cents, t.total, t.available
                        from events e join ticket_tiers t on t.event_id = e.id
                        where e.id = :eventId
                        order by t.price_cents, t.id""")
                .param("eventId", eventId)
                .query((rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        new EventDetail.Tier(
                                rs.getObject("tier_id", UUID.class),
                                rs.getString("tier_name"),
                                rs.getLong("price_cents"),
                                rs.getInt("total"),
                                rs.getInt("available"))))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.get(0);
        Map<UUID, EventDetail.Tier> tiers = new LinkedHashMap<>();
        rows.forEach(r -> tiers.put(r.tier().tierId(), r.tier()));
        return Optional.of(new EventDetail(first.eventId(), first.name(), first.city(), first.startsAt(),
                List.copyOf(tiers.values())));
    }
}
