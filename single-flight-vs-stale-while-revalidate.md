# Single-flight vs stale-while-revalidate

How the cache protects the database when a hot key expires. Source: `CacheLoader.java`, `ReadCache.java`
(`src/main/java/com/example/eventbooking/cache/`).

## 1. The problem

A hot key (say one popular event) gets 10,000 requests per second. When its Redis entry expires, every one of
those requests sees a miss in the same moment and runs the same query. That is a cache stampede.

Which database is hit: a request without a token reads from the **replica**, not the primary. Requests with a
fresh `X-Read-After` token read the primary and bypass Redis on purpose, so this protection is for the replica
read path. Primary reads are limited to recent writers, which keeps them rare.

## 2. Is "jitter + single-flight" the right strategy?

Partly. Each piece solves a different problem:

| Technique | What it protects against | Helps a single hot key? |
|---|---|---|
| Jitter (plus or minus 10% on the TTL) | Many different keys expiring at the same second | No. One key has one expiry time; jitter only spreads many keys apart |
| Single-flight | Many requests rebuilding the same missing key | Yes, but waiters add latency |
| Stale-while-revalidate | The key ever becoming missing in the normal case | Yes, and nobody waits |

The strategy used here combines all three: stale-while-revalidate for normal expiry, single-flight for a key that
is really gone, and jitter for the many-keys case.

## 3. Single-flight

When the entry is gone, one request rebuilds it and the others wait for that result.

- The first request becomes the leader and reads the replica. The others wait for the leader's answer.
- Waiters do not query the database.
- The cost is latency: waiters wait for the whole rebuild.

## 4. Stale-while-revalidate

Each entry stores two times:

- **Soft expiry**, 80% of the hard TTL (`app.cache.soft-ttl-ratio`). The entry is old but still usable.
- **Hard TTL**, the real Redis expiry. After it the key is gone.

Timeline for one hot key (hard TTL 60s, soft 48s):

| Time | What happens |
|---|---|
| 0s | The entry is written. |
| 0–48s | Every request is a plain hit. No database reads. |
| 48s | The entry is soft-expired, but the value is still in Redis. |
| 48.000s | The first request to arrive sees the soft expiry and takes the refresh lock. It is the rebuilder. |
| 48.000s onward | The other requests also see the soft expiry, fail to take the lock, and return the old value immediately. They do not wait. |
| about 48.05s | The rebuilder has read the replica and written a fresh entry with a new soft/hard expiry. Everything is back to plain hits. |

There is a rebuild, and only one request does it. Which request is just whoever gets the lock first. Nobody waits,
because everyone else is served the old value.

| | Single-flight | Stale-while-revalidate |
|---|---|---|
| Entry at expiry | Gone from Redis | Still in Redis |
| Rebuilders | One | One |
| Other requests | Wait for the rebuild | Get the old value instantly |
| Cost | Latency spike on every rebuild | The value is old for the rebuild time (milliseconds to tens of ms) |

## 5. Why both are needed

Stale-while-revalidate only works while the key still exists. The key can be missing when:

- the hard TTL passed because nobody read it between the soft expiry and the hard TTL
- a write invalidated it (`CacheInvalidator` deletes the event key on every booking)
- Redis was flushed or restarted

A booking on a hot event is the realistic case: the write deletes the key, and the next 10,000 requests all miss.
Only single-flight protects against that. So:

| Situation | Mechanism | What requests experience |
|---|---|---|
| Entry is past its soft expiry, key still exists | Stale-while-revalidate | Old value instantly, one background refresh |
| Key is missing (hard TTL, invalidation, flush) | Single-flight | One request loads, the others wait for it (up to `rebuild-wait`) |
| Many keys filled together | Jitter | Expiries spread over about 20% of the TTL |

## 6. Where and how each one is applied

All of it is in `CacheLoader.getOrLoad` (after the in-process L1 lookup, see `cache-advanche.md`), which `CachingEventReader` (event detail, listings) and
`CachingBookingReader` (bookings) call for every replica read.

```
getOrLoad(key)
 ├─ entry in this instance's memory (L1, 500 ms) ─────► return it                    (X-Cache: local)
 ├─ entry in Redis and not soft-expired ──────────────► return it                    (X-Cache: hit)
 ├─ entry in Redis and soft-expired ──────────────────► return it, start refresh     (X-Cache: stale)
 │     refreshInBackground: one per key per JVM (a Set), then one per cluster (Redis lock)
 └─ no entry ─────────────────────────────────────────► loadMissing                  (single-flight)
       ├─ another request in this JVM is already loading it ─► wait for its future   (X-Cache: coalesced)
       └─ this request is the JVM's leader
             ├─ wins the Redis lock ─► re-check Redis, else read replica, write Redis (X-Cache: miss)
             └─ loses the lock ──────► poll Redis until the other instance fills it  (X-Cache: coalesced)
```

### Stale-while-revalidate

- `ReadCache.put` stores `<softUntil epoch ms>|<json>` under a key with a hard TTL (jittered).
  `softUntil = now + hardTtl * soft-ttl-ratio`.
- `ReadCache.getEntry` returns the value with its `softUntil`.
- `CacheLoader.refreshInBackground` runs the refresh on a virtual thread, so the request that triggered it is not
  slowed down. It is guarded twice so a crowd produces one refresh:
  1. an in-process `Set` of keys being refreshed (the other 9,999 callers return at once)
  2. a Redis lock (`SET key token NX PX`), so with several app instances only one instance refreshes
- If the refresh fails, it is logged and the old entry keeps being served until the hard TTL. The next request
  tries again.

### Single-flight

- In one JVM: `CacheLoader.loading` is a map from key to the leader's `CompletableFuture`. The first request
  puts its future in the map and does the work. Later requests find it and wait on it.
- Across instances: the leader takes the Redis lock (`ReadCache.tryLock`). If it wins, it re-checks Redis (another
  instance may have just filled it), then reads the replica and writes the entry. If it loses, it polls Redis
  every 20ms until the entry appears.
- The lock is released with a Lua script that only deletes it if the token still matches, so a slow rebuilder
  cannot free someone else's lock.

## 7. Safety limits

- **Bounded waiting.** Waiters wait at most `app.cache.rebuild-wait` (1s). After that they load the value
  themselves, so a stuck or dead rebuilder cannot block requests.
- **Lock expiry.** `app.cache.rebuild-lock-ttl` (5s) frees the lock if its owner dies.
- **Failure is shared, not multiplied.** If the leader's database read fails, its waiters fail the same way
  instead of all retrying against a database that is already struggling. The next request after that tries again.
- **Redis outage.** Locks fail open: callers are told they may rebuild. An outage costs database reads (still
  coalesced within each JVM), never availability.
- **Not found is never cached.** A replica that has not caught up would otherwise freeze a "not found". Waiters
  still share the leader's answer for that one load.

## 8. Settings

```yaml
app.cache:
  soft-ttl-ratio: 0.8     # soft expiry = 80% of the hard TTL
  rebuild-lock-ttl: 5s    # rebuild lock safety expiry
  rebuild-wait: 1s        # how long waiters wait before loading themselves
```

A larger `soft-ttl-ratio` keeps entries fresh for longer but leaves a shorter refresh window before the hard
TTL. 0.8 of 60s leaves 12s, which is far more than a refresh needs.

## 9. How freshness is affected

- A write still deletes the entry (and again after the second-invalidation delay), so stale serving never
  extends past a write. After an invalidation the key is missing, and single-flight handles the next burst.
- A value served stale is at most one refresh old (milliseconds), and it is still bounded by the hard TTL.
- A background refresh reads the replica. If it races with a write and reads a lagging replica, the second
  invalidation removes it, as for any other fill.

## 10. How to see it

**UI:** run `scripts/ui.sh`. Every response shows `X-Served-By` and `X-Cache`. The "Hot key burst" panel fires
many concurrent reads of one event. Book that event first (this invalidates its entry), then fire the burst:
you should see 1 `miss` and the rest `coalesced` or `hit`.

**Measured on this stack** (300 concurrent requests right after deleting the key):
`1 miss, 12 coalesced, 287 hit`. One database read for 300 requests.

**Stale path** (polling one key every second): `hit` until about 47s, then one `stale` response, then `hit` again
with a fresh entry.

**Tests:** `CacheLoaderTest` sends 10,000 concurrent requests at a missing key (1 database read) and at a
soft-expired key (10,000 instant old values, 1 refresh, with the refresh held open to prove nobody waits). It also
covers waiting on another instance's lock, giving up on a dead rebuilder, not caching "not found", and failed
rebuilds and refreshes.
