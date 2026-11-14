# Cache TTL and invalidation

Reference for how the Redis read cache stays fresh. Source: `src/main/java/com/example/eventbooking/cache/`.

## 1. Two separate mechanisms

| | TTL (time to live) | Invalidation |
|---|---|---|
| What it is | An expiry time stored with each Redis key. Redis deletes the key itself when the time is up. | Code that deletes (or retires) entries when the data changes. |
| Trigger | Time passing. | A write: outbox row, Debezium, Kafka `outbox.event`, `CacheInvalidationConsumer`. |
| Role | Safety net and memory cleanup. Bounds how long any entry can live. | The main freshness mechanism for data that changes. |

TTL expiry does make an entry disappear, but it is not the same thing as invalidation. Invalidation reacts to a
specific change, so the cache is corrected within seconds. TTL only reacts to time, so on its own it would leave
the entry stale for up to the full TTL.

## 2. TTL is per key, not for the whole cache

Every cached record is its own Redis key with its own expiry clock, which starts when that key is written.

- `v1:event:<eventId>` is one key per event (60s)
- `v1:booking:<bookingId>` is one key per booking (10m)
- `v1:events:list:<scope>:<version>:<page>:<size>` is one key per listing page (30s)

Event A cached at 10:00:00 expires at about 10:01:00. Event B cached at 10:00:20 expires at about 10:01:20.
Nothing expires "as a whole".

## 3. Why bookings can use a 10 minute TTL

Bookings are cached only by id (`findBooking(bookingId)`). There is no cached "list of bookings".

- A booking row never changes after it is created, so a cached booking can never be out of date.
- A new booking has a new id, so it is never in the cache yet. The first read misses and goes to the replica.
- Empty results are not cached, so a replica that has not caught up yet cannot freeze a "not found".

So new bookings do not become invisible for 10 minutes. The 10m TTL is only there so unused entries are
eventually removed from Redis. Bookings have no invalidation, since there is nothing to invalidate.

Event details are different. Availability changes on every booking, so they get a short TTL (60s) and are
actively invalidated.

## 4. Jitter (plus or minus 10% on the TTL)

`ReadCache.put` adds a random offset of up to 10% of the TTL.

Scenario: the app starts, or Redis is flushed, and a burst of traffic caches 1000 event details in the same
second. With a fixed 60s TTL they all expire at the same second. The next burst of requests misses on all 1000
at once and sends 1000 queries to the replica together (a cache stampede). The same spike then repeats every
minute.

With jitter, each key gets between 54s and 66s, so expiries spread over about 12 seconds. Misses arrive
gradually and the database load stays smooth.

## 5. Why a second invalidation

`CacheInvalidator.invalidateEvent` evicts twice: immediately, and again after
`app.cache.second-invalidation-delay` (equal to the read-after window, 5s by default).

The problem it solves is a race with replication lag:

1. t=0s: a booking commits on the primary. The outbox row is replicated to Kafka by Debezium, which reads the
   primary's WAL. The first invalidation runs at about t=0s and deletes the cached event.
2. t=1s: a reader (no token) misses the cache and reads the **replica**, which is still 3s behind. It gets the
   old event and writes it into Redis with a 60s TTL.
3. Without a second invalidation, that old value would be served until t=61s.
4. t=5s: the second invalidation runs. The replica has caught up, and the stale entry is deleted. The next read
   fetches the fresh value.

So the second invalidation limits staleness to roughly the replica lag instead of a full TTL. If the second
invalidation fails, the code logs a warning and the entry expires by TTL.

Listings use a version number in the key. One `bump` retires every page and size of a listing at once, without
scanning Redis. The old keys are never read again and disappear by their own TTL.

## 6. Why it stays fresh

- **Replica:** it applies the primary's WAL continuously, so it is seconds behind at worst. After 10 minutes it
  has caught up.
- **TTL:** an entry can never outlive its TTL: event detail 60s, listing 30s, booking 10m (immutable).
- **Invalidation:** a write clears the affected event and listing entries within seconds.
- **Second invalidation:** removes a stale value that was cached from a lagging replica.
- **Primary reads bypass Redis:** a caller with a fresh `X-Read-After` token neither reads nor fills the cache.

Cases where data can still be stale:
- the replica is stopped, or replication is stuck or badly lagging
- Kafka or Debezium is down, so invalidation does not run. Event and listing entries then fall back to their TTL
  (60s and 30s)
- the artificial replica lag (`scripts/set-replica-lag.sh`) is set above the read-after window

## 7. TTL implementation

### 7.1 The values: `src/main/resources/application.yml`

```yaml
cache:
  event-ttl: 60s
  list-ttl: 30s
  booking-ttl: 10m
```

`CacheProperties.java` binds these to `app.cache.*`.

### 7.2 Call sites choose which TTL to use

- `CachingEventReader.java:63`: city listings use `properties.listTtl()`
- `CachingEventReader.java:80`: event detail uses `properties.eventTtl()`
- `CachingBookingReader.java:46`: bookings use `properties.bookingTtl()`

### 7.3 Redis expiry is set in `ReadCache.put`

```java
public void put(String key, Object value, Duration ttl) {
    long millis = ttl.toMillis();
    long jitter = ThreadLocalRandom.current().nextLong(-millis / 10, millis / 10 + 1);
    redis.opsForValue().set(key, json, Duration.ofMillis(millis + jitter));
}
```

This is a Redis `SET` with an expiry, so Redis removes the key itself. The stored value is wrapped as
`<softUntil epoch ms>|<json>`; see `single-flight-vs-stale-while-revalidate.md` for the soft expiry. Check a key with `redis-cli TTL <key>`.

Related: the listing version key has a 1 day TTL (`VERSION_TTL` in `ReadCache.java`).
