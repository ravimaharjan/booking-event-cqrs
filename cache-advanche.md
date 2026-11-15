# Scaling reads to 200K requests per second

Requirement, approach and results for taking the read side from about 10K requests per second per instance to a
200K peak. This is a working document: the results section is filled in as each step is built and measured.

## 1. Requirement

| | |
|---|---|
| Traffic | **200,000 requests per second at peak** on the event listing and booking list views |
| Goal | The database and the app stay healthy and latency stays low at that rate |
| Pages | Event listing by city, event detail (public, same for everyone). Booking list (private, per user) |
| Freshness | A few seconds of staleness is acceptable for anonymous readers. A user who just wrote must still see their own write (read-after-write token, either LSN or time based: see docs/read-your-writes-lsn.md and docs/read-your-writes-5s.md) |

The booking list is private per user. This project has no "list my bookings" endpoint yet, only "view booking by id".

## 2. Where we start (measured)

On the dev laptop, k6 and everything else on the same machine (`docs/load-and-capacity-tests.md`):

- One app instance sustains about **10,000 requests per second** on a hot key, using about 7 CPU cores
  (about 0.7 cores per 1,000 requests per second).
- The database is not the limit. A hot key costs the replica almost nothing (about 16 transactions per 30-second run).
- Memory is not the limit. CPU is.

What would break at 200K:

| Resource | Needed at 200K | Why that is a problem |
|---|---|---|
| App instances | about 20 | A lot of CPU just to return cached data |
| Redis calls | about 400K per second (a listing makes 2 calls: version key and entry) | One Redis node handles roughly 100K per second |
| CPU per request | Each hit parses the stored JSON twice and serializes it again | Wasted work on every request |

## 3. Approach

The idea is to stop most requests from reaching the app at all, then make the ones that do reach it cheaper.
Each layer takes load off the next one.

| # | Step | What it does | Status |
|---|---|---|---|
| 1 | **HTTP cache headers + a caching proxy in front** | The app marks public GET responses as reusable for 1 second (`Cache-Control: public, s-maxage=1`). A proxy (nginx here, a CDN in production) answers repeated requests from memory and asks the app once per second per URL | Done (section 4) |
| 2 | **In-process cache (L1)** | Each app instance keeps hot entries for about 500 ms (`app.cache.local-ttl`), so they stop reaching Redis | Done (section 6) |
| 3 | **Serve stored JSON bytes** | Store the response bytes in Redis and write them straight to the response, skipping parse and serialize | **Not built**, measured at under 1% (section 7) |
| 4 | **More app instances behind a load balancer** | The app is stateless, so this is configuration | Not done |

### Why a CDN or proxy works for API responses

A CDN caches any HTTP GET whose response says it may be cached. It does not care if the body is JSON. The cache key
is the URL including the query string, so `/api/queries/events?city=Berlin&page=0` is one cacheable item.

- Cached: the public listing and event-detail GETs.
- Never cached: commands (POST create event, POST book).
- **Bypassed: any request with an `X-Read-After` token.** That user must see their own write, so the proxy skips its
  cache for them and does not store their response.
- Origin load is about (distinct URLs) x (proxy or edge servers) / (cache lifetime in seconds), instead of the
  full request rate.

### What the 1-second header means

```
Cache-Control: public, max-age=0, s-maxage=1, stale-while-revalidate=5
```

- `s-maxage=1`: shared caches (proxy, CDN) may reuse the response for 1 second without asking the app.
- `max-age=0`: browsers always revalidate, so only the shared cache is involved.
- `stale-while-revalidate=5`: for up to 5 more seconds after expiry the proxy may serve the old copy while it fetches
  a new one in the background. Same idea as in the app's own cache.

The cost is that anonymous readers can see data up to about 1 second older.

## 4. Step 1: headers and a caching proxy

### What was built

- `HttpCachePolicy` decides the `Cache-Control` header for each query response:
  - anonymous read answered from the replica or cache: `public, max-age=0, s-maxage=1, stale-while-revalidate=5`
  - read served by the primary (token holders), and every 404: `no-store`
  - settings: `app.http-cache.*` (`HTTP_CACHE_ENABLED=false` turns it off)
- `infra/nginx/nginx.conf` and an `edge` service in `infra/docker-compose.yml` (port **8088**; the app is still
  directly on 8080):
  - caches `/api/queries/` GET responses using the app's `Cache-Control`
  - `proxy_cache_lock`: if many requests miss at once, only one goes to the app (request collapsing)
  - `proxy_cache_use_stale updating` + `proxy_cache_background_update`: serve the old copy while refreshing
  - `proxy_cache_bypass` and `proxy_no_cache` on `X-Read-After`: token requests skip the cache entirely
  - adds `X-Proxy-Cache: HIT | MISS | EXPIRED | UPDATING | STALE | BYPASS` to every response
- The UI shows an `edge` badge from `X-Proxy-Cache`. Point it at the proxy with `API_URL=http://localhost:8088 scripts/ui.sh`.

## 5. Results

Filled in below as each step is measured with `scripts/capacity-test.sh`.

### Step 1 results: hot key through the caching proxy (`BASE_URL=http://localhost:8088`)

Same laptop, same app. k6, nginx, Docker's port proxy and the app all share 20 cores. Pass rule as in
`docs/load-and-capacity-tests.md`.

| Target RPS | Achieved | p95 | Result | App CPU (cores) | Proxy CPU | k6 CPU | Host CPU | Replica transactions |
|---|---|---|---|---|---|---|---|---|
| 10,000 | 9,994 | 0.5 ms | PASS | 0.0 | 1.2 | 2.7 | 25% | 17 |
| 20,000 | 20,000 | 1.3 ms | PASS | 0.0 | 2.3 | 4.6 | 45% | 16 |
| 30,000 | 29,946 | 1.4 ms | PASS | 0.0 | 2.5 | 5.0 | 50% | 17 |
| 40,000 | 39,877 | 2.1 ms | PASS | 0.0 | 2.6 | 5.4 | 54% | 17 |
| 60,000 | 59,353 | 12.0 ms | PASS | 0.0 | 3.5 | 8.4 | 77% | 17 |
| 80,000 | 66,964 | 31.6 ms | FAIL | 0.0 | 3.7 | 11.0 | 92% | 17 |
| 100,000 | 72,928 | 47.2 ms | FAIL | 0.0 | 3.6 | 11.3 | 94% | 17 |

Many keys (20 events plus 5 city listings): 20,000 passed (p95 1.7 ms) and 40,000 passed (p95 2.2 ms). The replica saw
about 55 transactions per run instead of 17, one per distinct key.

What this shows:

- **Before: about 10,000 RPS per instance, 7 CPU cores. With the proxy: at least 60,000 RPS, and the app used almost no
  CPU.** Requests are answered by the proxy and the app is asked about once per second per URL.
- **The failures at 80K and 100K are the load generator, not the system.** k6 used 11 of the 20 cores and the host was
  92–94% busy, while the proxy used only 3.7 cores and the app none. The real ceiling is higher than 60K and is not
  measured here. A separate load-generator machine is needed to find it.
- **Extrapolation to 200K (an estimate, not measured):** the proxy used about 0.06 cores per 1,000 RPS, so 200K is
  roughly 12 cores of proxy, spread over a few proxy nodes or handled by a CDN.
- **Correctness checks (curl):** repeated requests go MISS then HIT; a request with `X-Read-After` gets BYPASS and
  `Cache-Control: no-store`; a 404 is `no-store` and not cached; after the 1-second lifetime the proxy serves STALE
  once while it refreshes, then HIT again.
- **This only helps requests that are identical and public.** Per-user pages (a booking list) and requests with the
  token still reach the app, so steps 2 and 3 matter for them.

## 6. Step 2: in-process cache (L1)

### What was built

- `LocalCache` (Caffeine): each app instance keeps entries in its own memory for `app.cache.local-ttl` (500 ms),
  bounded by `app.cache.local-max-entries` (10,000). Setting the TTL to 0 disables it.
- `CacheLoader` now looks in order at: this instance's memory, then Redis, then the database. A memory hit is reported
  as `X-Cache: local`.
- The listing version number (a second Redis call on every listing request) is also kept in memory, so a listing
  request no longer calls Redis at all on a hit.
- The Redis value is now stored as `<softUntil>|<json>` and parsed once (before, it was parsed twice).

### Freshness cost

An instance can serve an entry up to 500 ms past a change. A write clears the local copy on the instance that
processes the invalidation message. Other instances' copies expire by themselves within 500 ms. Token requests bypass
every cache layer, so read-after-write is unaffected.

### Results: direct to the app (port 8080, no proxy)

| Target RPS | Achieved | p95 | Result | App CPU avg/peak (cores) | Redis CPU | k6 CPU | Host CPU |
|---|---|---|---|---|---|---|---|
| 10,000 | 9,975 | 2.9 ms | PASS | 3.1 / 7.8 | 0.02 | 3.3 | 44% |
| 14,000 | 13,997 | 2.5 ms | PASS | 3.4 / 4.3 | 0.02 | 4.0 | 49% |
| 22,000 | 21,966 | 1.8 ms | PASS | 3.8 / 4.2 | 0.02 | 4.4 | 53% |
| 30,000 | 29,934 | 2.3 ms | PASS | 4.0 / 4.1 | 0.02 | 4.7 | 57% |
| 40,000 | 39,862 | 8.2 ms | PASS | 5.4 / 6.5 | 0.02 | 6.4 | 77% |
| 50,000 | 49,131 | 18.1 ms | PASS | 5.6 / 6.6 | 0.02 | 7.6 | 85% |

Many keys (mixed): 10,000, 20,000 and 30,000 all passed (p95 1.6, 1.8 and 3.2 ms; app 4.7 cores at 30,000).
Replica transactions stayed at the background level (about 17 for one hot key, about 52 for many keys).

Compared with the start (same machine, same test): the app handled about 10,000 RPS using 7 cores before, and 50,000
RPS using 5.6 cores now. That is **about 0.7 cores per 1,000 RPS before and about 0.11 after**, roughly 6x more efficient.
The previous run failed at 12,000; this one passed at 50,000 (the load generator and host CPU are close to their
limit there, so the true ceiling is higher).

## 7. Step 3: serve stored JSON bytes (not built)

The idea was to skip parsing and serializing JSON on every hit. I measured those costs before building it
(Jackson 2.17, a typical 359-byte event, JIT warmed):

| Operation | Cost |
|---|---|
| Serialize the response | about 0.8 microseconds |
| Parse the stored value | about 1.8 microseconds |
| Total CPU per request at 50,000 RPS | about 110 microseconds (5.6 cores / 50,000) |

Serialization is under 1% of the per-request cost, and parsing is now skipped on memory hits. Building this would
mean changing the reader interfaces, the cache decorators and the controller to carry raw JSON, for a gain below the
noise of the measurements. I decided not to. The real cost was the Redis round trip, which step 2 removed.

I had expected JSON handling to matter, and the measurement says it did not. If profiling later shows otherwise,
this is the next thing to try.

## 8. Summary and the 200K target

| Setup | Measured on this laptop | App CPU per 1,000 RPS |
|---|---|---|
| Start: Redis only, direct to the app | passes at 10,000, fails at 12,000 | 0.7 cores |
| Step 2: plus in-process cache, direct to the app | passes at 50,000 | 0.11 cores |
| Step 1: plus caching proxy | passes at 60,000, app idle (limit was the load generator) | about 0 |

Estimates for 200,000 RPS (extrapolated, not measured):

- **Direct to the app with the in-process cache:** about 22 cores of app, which is a handful of instances.
  Before this work it was about 140 cores.
- **Behind a caching proxy or CDN:** the proxy needs about 12 cores (0.06 per 1,000), spread over a few nodes. The app sees a
  few hundred requests per second.
- **What must still be proven:** the 200K figure needs load generators on other machines and more than one app
  instance. This laptop cannot generate or absorb it.

Things this does not cover:

- The booking list (private per user) is not served by a shared cache. It benefits from step 2 but has no endpoint yet.
- Write throughput is untouched.
- **Step 4, running multiple app instances behind a load balancer, is not done.** All measurements used a single app
  instance, so the 200K figures are extrapolations. Things this leaves unproven:
  - the load balancer itself (its capacity, connection limits and health checks)
  - the cross-instance behaviour that so far only unit tests cover: the Redis rebuild lock, and waiting on another
    instance's rebuild
  - invalidation across instances: the Kafka consumer group delivers each message to only one instance, so the other
    instances' in-process copies are not cleared by it and expire by themselves within 500 ms
  - whether Redis, which one node serves at roughly 100K operations per second, stays idle at that scale
- Up to about 1.5 seconds of extra staleness for anonymous readers (1 s proxy + 0.5 s in-process), on top of the
  existing replica lag and invalidation delay.
