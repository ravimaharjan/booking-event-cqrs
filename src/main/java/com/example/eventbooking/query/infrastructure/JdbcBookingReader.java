package com.example.eventbooking.query.infrastructure;

import com.example.eventbooking.consistency.ReadRouter;
import com.example.eventbooking.consistency.ReadSource;
import com.example.eventbooking.query.application.BookingReader;
import com.example.eventbooking.query.application.BookingView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBookingReader implements BookingReader {

    private final ReadRouter router;

    JdbcBookingReader(ReadRouter router) {
        this.router = router;
    }

    @Override
    public Optional<BookingView> findBooking(UUID bookingId, ReadSource source) {
        return router.forSource(source).sql("""
                        select id, event_id, tier_id, quantity, customer_email, created_at
                        from bookings where id = :id""")
                .param("id", bookingId)
                .query((rs, i) -> new BookingView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tier_id", UUID.class),
                        rs.getInt("quantity"),
                        rs.getString("customer_email"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public List<BookingView> listBookings(UUID eventId, int page, int size, ReadSource source) {
        return router.forSource(source).sql("""
                        select id, event_id, tier_id, quantity, customer_email, created_at
                        from bookings
                        where (cast(:eventId as uuid) is null or event_id = :eventId)
                        order by created_at desc, id
                        limit :limit offset :offset""")
                .param("eventId", eventId)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, i) -> new BookingView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tier_id", UUID.class),
                        rs.getInt("quantity"),
                        rs.getString("customer_email"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
