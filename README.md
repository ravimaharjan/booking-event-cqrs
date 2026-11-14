# Event Booking - CQRS with Efficient Caching Strategy

Spring Boot 3 / Java 21 / PostgreSQL (primary + streaming replica) / Redis / Kafka + Debezium, with a React web UI.

## Objectives

- **Sustain high-concurrency traffic (10,000+ requests per second) without proportional database load.** Hot read
  paths are served from cache, and a burst of requests for one expired key results in a single database read.
- **Demonstrate the CQRS pattern with a read/write database split.** Commands write to a dedicated primary
  database; queries read from a streaming replica through a denormalized read model.
- **Implement a multi-layered caching strategy.** Redis cache-aside with TTL and jitter, event-driven invalidation
  (transactional outbox, Debezium, Kafka) with a delayed second invalidation, and cache-stampede protection
  (single-flight rebuild plus stale-while-revalidate).
- **Demonstrate the read-after-write (read-your-writes) consistency pattern.** A consistency token routes a user's
  follow-up reads to the primary until the replica and cache have caught up.

## System Architecture

Commands write to the primary in one transaction (normalized tables, the denormalized `event_listing` view and an
outbox row). Debezium publishes the outbox row to Kafka, and a consumer invalidates Redis. Queries are served from
Redis, falling back to the replica, or to the primary for a user who just wrote.

Block diagram, component roles, read/write/invalidation flows and failure behaviour:
**[docs/System Architecture.md](docs/System%20Architecture.md)**

## Feature Capabilities

**Commands (write, primary)**
- Create an event with one or more ticket tiers.
- Book tickets for a tier. Overselling is prevented by an atomic conditional update (`available >= qty`) plus a
  database check constraint; a sold-out booking returns 409.

**Queries (read, replica or cache)**
- View an event with its tiers and remaining availability.
- List events by city (paginated).
- View a booking.

**Consistency and caching behaviour**
- Every command returns an `X-Read-After` token; reads that present a fresh token are served by the primary.
- Every query response says who answered (`X-Served-By`: `cache`, `replica` or `primary`) and what the cache did
  (`X-Cache`: `hit`, `stale`, `miss`, `coalesced` or `bypass`).

### Web UI views

Start it with `scripts/ui.sh` (see [Web UI](#web-ui)). The page has these views:

| View | What it does |
| ---- | ------------ |
| Status bar | Shows whether the API is reachable. |
| Read-after token bar | Shows the latest `X-Read-After` token and its age, and a switch to send it on reads (off = anonymous read from replica/cache; on and recent = primary). |
| Create event | Form for name, city, start time and ticket tiers (name, price in cents, quantity). |
| Book tickets | Pick an event and tier, set quantity and email, and book. "Load tiers" fills the tier list. |
| View event | Fetch one event by id. Recently created ids are offered as shortcuts. |
| List events by city | Fetch a page of events for a city (or all cities). |
| View booking | Fetch one booking by id. |
| Hot key burst | Fires many concurrent reads of one event and shows how many were `hit`, `stale`, `miss` or `coalesced`, with latency percentiles. |
| Last response | Method, path, status, `X-Served-By`, `X-Cache`, latency and the JSON body. |
| Request log | History of every call with the same details; click a row to see its response. |

## Database Schema

Managed by Flyway (`src/main/resources/db/migration`). All writes go to the primary; the replica receives every
table through streaming replication.

| Table | Side | What it stores |
| ----- | ---- | -------------- |
| `events` | Write model | One row per event: id, name, city, start time, created time. |
| `ticket_tiers` | Write model | Ticket tiers of an event: name, price in cents, `total` seats and `available` seats. A check constraint keeps `0 <= available <= total`. |
| `bookings` | Write model | One row per booking: event, tier, quantity, customer email, created time. |
| `event_listing` | Read model (denormalized) | One row per event with the fields a listing needs: name, city, start time, lowest price and total available tickets. Indexed by `(city, starts_at, event_id)`. Updated in the same transaction as the write model, so replication delivers both together. |
| `outbox` | Integration | Change announcements (aggregate, type, JSON payload). A row is inserted and deleted in the same transaction as the business change; Debezium reads the insert from the WAL and publishes it to Kafka, so the table stays empty. |

## Strategy: read-after-write token (timestamp)

Commands write to the **primary**; queries read from a **replica**. Replication lag means a user who just
booked a ticket may read stale data (or a 404) from the replica. The token fixes that:

1. Every command response carries `X-Read-After: <commit time, epoch ms>`, issued after the commit.
2. The client sends it back on follow-up reads.
3. If the token is younger than `app.consistency.read-after-window` (default 5s, keep it above worst-case
   replication lag), the read goes to the **primary**. Otherwise (or with no token, e.g. anonymous visitors)
   it goes to a **replica**.
4. `X-Served-By: primary|replica|cache` on every query response shows which one answered.

Anonymous browsing (the high-volume read load) never touches the primary. Only users who just wrote do, and only
for a few seconds. Walkthrough: [docs/read-after-header-walkthrough.md](docs/read-after-header-walkthrough.md).

## Caching Strategy

Anonymous reads go cache-aside through Redis. Entries have a TTL with jitter and a soft expiry; commands write an
outbox row that Debezium publishes to Kafka (`outbox.event`), and a consumer invalidates the affected keys (twice,
the second time after the replica has caught up). When a hot entry expires, only one request rebuilds it.
Reads carrying a recent `X-Read-After` token bypass Redis. Disable the cache with `CACHE_ENABLED=false`.

Layers in front of Redis, for high read volume: an in-process cache per app instance (500 ms) and an optional
caching proxy on port **8088** (nginx, standing in for a CDN; the app itself is on 8080). Public query responses carry
`Cache-Control: s-maxage=1`; requests with an `X-Read-After` token bypass the proxy. Measured effect and design:
[cache-advanche.md](cache-advanche.md).

Detailed documents:

- **[cache-ttl-and-invalidation.md](cache-ttl-and-invalidation.md)**: how TTL, jitter and invalidation keep the
  cache fresh, why there is a second invalidation, and where the TTLs are implemented.
- **[cache-advanche.md](cache-advanche.md)**: scaling reads toward 200K requests per second (caching proxy, `Cache-Control`,
  in-process cache), with measured results.
- **[single-flight-vs-stale-while-revalidate.md](single-flight-vs-stale-while-revalidate.md)**: how a hot key
  expiring under 10,000 concurrent requests results in one database read, and where each technique is applied.

## Run

There are three ways to run it. The databases always run in Docker (primary on `:15432`, replica on `:15433`).
Only the app itself can run either in a container or directly on your machine.

Docker files live in `infra/` (`docker-compose.yml`, `Dockerfile`); the scripts below already point at them. Run
raw `docker compose` commands from the repo root with `-f infra/docker-compose.yml`.

### A. Everything in containers (primary, replica, Redis, Kafka, Debezium and app)

```
scripts/start.sh                 # background; waits until the API on :8080 is healthy
scripts/start.sh --foreground    # interactive: logs stream in this terminal, Ctrl+C stops everything
scripts/stop.sh                  # stop, keep database data
scripts/stop.sh --clean          # stop and delete all data (setup scripts run again on next start)
```

Watching logs in background mode:

```
tail -f logs/app.log             # file mounted from the container
docker compose -f infra/docker-compose.yml logs -f app       # same output, console stream
docker compose -f infra/docker-compose.yml logs -f           # app, primary and replica together
```

Debug logs are off by default: `APP_LOG_LEVEL=DEBUG scripts/start.sh`.

### B. App locally, databases in containers

Useful for debugging in an IDE or fast restarts without rebuilding the image.

Requirements: **JDK 21** and **Maven 3.9+** installed locally (Java 11 will not work), plus Docker.

```
docker compose -f infra/docker-compose.yml up -d primary replica     # start only the databases
mvn spring-boot:run                      # runs in this terminal; logs print here and go to logs/app.log
```

Ctrl+C stops the app. Stop the databases with `docker compose -f infra/docker-compose.yml down` (add `-v` to delete data).
The defaults in `application.yml` already point at `localhost:15432` / `localhost:15433`; override with the
`PRIMARY_URL`, `REPLICA_URL`, `APP_LOG_LEVEL` environment variables if needed, for example:

```
APP_LOG_LEVEL=DEBUG mvn spring-boot:run
```

Do not run A and B at the same time, both use port 8080. If the containerised app is running, stop it first
with `docker compose -f infra/docker-compose.yml stop app`.

### C. No local JDK or Maven

Run the Maven build inside a container while keeping the databases from compose:

```
docker compose -f infra/docker-compose.yml up -d primary replica
docker run --rm --network host -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-21 mvn spring-boot:run
```

### Web UI

A small React + Vite app to create events, book tickets, and view events, listings and bookings. Every response
shows who answered (`X-Served-By`) and what the cache did (`X-Cache`: hit, stale, miss, coalesced, bypass), plus a
request log and a hot-key burst test.

```
scripts/ui.sh                    # needs Node 18+; starts the UI on http://localhost:5173 and opens the browser
```

The backend must already be running (`scripts/start.sh`); the script stops with a hint if it is not. The first run
does `npm install` in `ui/`. Set `API_URL=http://host:8080` to point the UI at another backend.

### Demos and load test

```
scripts/demo.sh                  # stale read vs. read-after-write, side by side
scripts/cache-demo.sh            # Redis cache, outbox + Debezium + Kafka invalidation
scripts/set-replica-lag.sh 10s   # change artificial replica lag (0 to disable)
scripts/capacity-test.sh         # (BASE_URL=http://localhost:8088 goes through the caching proxy) steps the read rate up (hot key; TARGET=mixed for many keys), prints pass/fail, CPU and memory
k6 run k6/load-test.js           # ~500 rps reads, 5 rps bookings, asserts zero stale reads after writes
```

What both tests check and their pass criteria: [docs/load-and-capacity-tests.md](docs/load-and-capacity-tests.md).

The replica starts with a 3s artificial apply delay (`REPLICA_APPLY_DELAY`) so staleness is reproducible.

## API

| Method | Path                                    | Notes                                                          |
| ------ | --------------------------------------- | -------------------------------------------------------------- |
| POST   | `/api/commands/events`                  | `{name, city, startsAt, tiers:[{name, priceCents, quantity}]}` |
| POST   | `/api/commands/events/{id}/bookings`    | `{tierId, quantity, customerEmail}`; 409 if sold out           |
| GET    | `/api/queries/events?city=&page=&size=` | from `event_listing`                                           |
| GET    | `/api/queries/events/{id}`              | event with tiers                                               |
| GET    | `/api/queries/bookings/{id}`            |                                                                |

Unit tests: `docker build -f infra/Dockerfile .` runs them (no local Maven/JDK 21 required).
