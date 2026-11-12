# Event Booking – CQRS with read-after-write consistency

Spring Boot 3 / Java 21 / PostgreSQL (primary + streaming replica). Backend API only.

## The problem this demonstrates

Commands write to the **primary**; queries read from a **replica**. Replication lag means a user who just
booked a ticket may read stale data (or a 404) from the replica.

## Strategy: read-after-write token (timestamp)

1. Every command response carries `X-Read-After: <commit time, epoch ms>`, issued after the commit.
2. The client sends it back on follow-up reads.
3. If the token is younger than `app.consistency.read-after-window` (default 5s, keep it above worst-case
   replication lag), the read goes to the **primary**. Otherwise (or with no token, e.g. anonymous visitors)
   it goes to a **replica**.
4. `X-Served-By: primary|replica` on every query response shows which one answered.

Anonymous browsing (the 100k-visitor load) never touches the primary. Only users who just wrote do, and only
for a few seconds.

## Layout

```
command/{api,application,domain,infrastructure}   write side (primary only, transactional)
query/{api,application,infrastructure}            read side (ports: EventReader, BookingReader)
consistency/                                      token issuing + primary/replica routing
config/                                           data sources, error handling
```

- Write model is normalized (`events`, `ticket_tiers`, `bookings`). The denormalized `event_listing` read model
  is updated in the **same transaction**, so replication delivers both together. No message broker needed.
- Overselling is prevented by an atomic conditional update (`available >= qty`) plus a DB check constraint.
- Caching later: implement a decorator of `EventReader`/`BookingReader` (e.g. Redis) and mark it `@Primary`.
  Reads resolved to `PRIMARY` should bypass the cache.

## Run

There are three ways to run it. The databases always run in Docker (primary on `:15432`, replica on `:15433`).
Only the app itself can run either in a container or directly on your machine.

### A. Everything in containers (primary, replica and app)

```
scripts/start.sh                 # background; waits until the API on :8080 is healthy
scripts/start.sh --foreground    # interactive: logs stream in this terminal, Ctrl+C stops everything
scripts/stop.sh                  # stop, keep database data
scripts/stop.sh --clean          # stop and delete all data (setup scripts run again on next start)
```

Watching logs in background mode:

```
tail -f logs/app.log             # file mounted from the container
docker compose logs -f app       # same output, console stream
docker compose logs -f           # app, primary and replica together
```

Debug logs are off by default: `APP_LOG_LEVEL=DEBUG scripts/start.sh`.

### B. App locally, databases in containers

Useful for debugging in an IDE or fast restarts without rebuilding the image.

Requirements: **JDK 21** and **Maven 3.9+** installed locally (Java 11 will not work), plus Docker.

```
docker compose up -d primary replica     # start only the databases
mvn spring-boot:run                      # runs in this terminal; logs print here and go to logs/app.log
```

Ctrl+C stops the app. Stop the databases with `docker compose down` (add `-v` to delete data).
The defaults in `application.yml` already point at `localhost:15432` / `localhost:15433`; override with the
`PRIMARY_URL`, `REPLICA_URL`, `APP_LOG_LEVEL` environment variables if needed, for example:

```
APP_LOG_LEVEL=DEBUG mvn spring-boot:run
```

Do not run A and B at the same time, both use port 8080. If the containerised app is running, stop it first
with `docker compose stop app`.

### C. No local JDK or Maven

Run the Maven build inside a container while keeping the databases from compose:

```
docker compose up -d primary replica
docker run --rm --network host -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-21 mvn spring-boot:run
```

### Demos and load test

```
scripts/demo.sh                  # stale read vs. read-after-write, side by side
scripts/set-replica-lag.sh 10s   # change artificial replica lag (0 to disable)
k6 run k6/load-test.js           # ~500 rps reads, 5 rps bookings, asserts zero stale reads after writes
```

The replica starts with a 3s artificial apply delay (`REPLICA_APPLY_DELAY`) so staleness is reproducible.

## API

| Method | Path                                    | Notes                                                          |
| ------ | --------------------------------------- | -------------------------------------------------------------- |
| POST   | `/api/commands/events`                  | `{name, city, startsAt, tiers:[{name, priceCents, quantity}]}` |
| POST   | `/api/commands/events/{id}/bookings`    | `{tierId, quantity, customerEmail}`; 409 if sold out           |
| GET    | `/api/queries/events?city=&page=&size=` | from `event_listing`                                           |
| GET    | `/api/queries/events/{id}`              | event with tiers                                               |
| GET    | `/api/queries/bookings/{id}`            |                                                                |

Unit tests: `docker build .` runs them (no local Maven/JDK 21 required).
