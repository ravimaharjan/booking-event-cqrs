# Read-your-writes, approach 2: replica position (LSN)

This is the **lsn** approach (`app.consistency.mode=lsn`, the default). The other supported approach, a fixed
time window, is in [read-your-writes-5s.md](read-your-writes-5s.md). Use one or the other; they are not combined.

Instead of guessing how long replication takes, the read asks the replica directly: "have you applied the
write this client just made?"

## How it works

1. A command commits on the primary. Right after the commit the API reads the primary's current WAL position,
   `SELECT pg_current_wal_lsn()`, and returns it in the `X-Read-After` response header (for example `0/16B3748`).
   The position is taken after the commit, so it is at or past the commit record.
2. The client sends that value back in `X-Read-After` on follow-up reads.
3. For a read with a token, the API asks the replica:
   `SELECT pg_last_wal_replay_lsn() >= CAST(:token AS pg_lsn)`.
   - **true**: the replica has applied the write, so the read goes to the **replica**.
   - **false**: the API re-checks every 10 ms for up to `app.consistency.lsn-wait-timeout` (default 100 ms).
     If the replica still has not reached the token, the read goes to the **primary**.
4. No token, or a blank one, goes straight to the replica (anonymous visitors).
5. `X-Served-By: primary|replica|cache` on every query response shows which one answered.

## The rules (from `ReadSourceResolver`, lsn mode)

| Request | Routed to |
|---|---|
| No `X-Read-After` header (or blank) | **replica** |
| Replica has replayed up to the token's LSN | **replica** |
| Replica still behind after `lsn-wait-timeout` | **primary** |
| LSN check fails (replica unreachable, query error) | **primary** (the primary is always correct) |
| Token not shaped like an LSN (`hex/hex`) | **400** (`InvalidReadAfterTokenException`), the replica is not queried |

- The token is an opaque string to clients. It is compared by the database, not by the application, so clients
  must not parse or compare it.
- An LSN only moves forward, so a later token covers every earlier write. The client should send the most
  recent token it has received. An older token is also satisfied once the newer one is.
- Test setup: the replica applies WAL with an artificial delay of **3s** (`REPLICA_APPLY_DELAY`). That delay
  holds back `pg_last_wal_replay_lsn()`, so for about 3 seconds after a write the replica is behind the token.

## Setup

```bash
scripts/start.sh            # CONSISTENCY_MODE defaults to lsn
BASE=http://localhost:8080
```

Settings (env var, property):

| Env var | Property | Default | Meaning |
|---|---|---|---|
| `CONSISTENCY_MODE` | `app.consistency.mode` | `lsn` | `lsn` or `time` |
| `LSN_WAIT_TIMEOUT` | `app.consistency.lsn-wait-timeout` | `100ms` | How long a read waits for the replica before using the primary. `0` falls back immediately. |

`READ_AFTER_WINDOW` is not used in this mode (see the cache note below for the one place it still matters).

## Timeline

`T` is seconds since the event was created. Token values are illustrative; yours will differ.

### T+0.0s: create an event (command, always the primary)

```bash
curl -i -X POST $BASE/api/commands/events -H 'Content-Type: application/json' -d '{
  "name": "Demo Concert", "city": "Berlin", "startsAt": "2027-01-01T20:00:00Z",
  "tiers": [{"name": "General", "priceCents": 5000, "quantity": 100}]}'
```

```
HTTP/1.1 201
Location: /api/queries/events/6f1c...
X-Read-After: 0/16B3748            <-- token = primary WAL position after the commit
{"id":"6f1c..."}
```

```bash
EVENT_ID=6f1c...
TOKEN=0/16B3748
```

### T+0.5s: read WITHOUT a token

```bash
curl -i $BASE/api/queries/events/$EVENT_ID
```

```
HTTP/1.1 404
X-Served-By: replica
```

No token, so the replica is used. It is 3s behind, so the event is not there yet: a stale read (404).
This is the problem the header solves.

### T+0.5s: read WITH the token

```bash
curl -i $BASE/api/queries/events/$EVENT_ID -H "X-Read-After: $TOKEN"
```

```
HTTP/1.1 200
X-Served-By: primary
```

The replica's replay position is behind `0/16B3748`. The API re-checks for 100 ms, the replica does not move
(it is holding WAL for 3s), so the read falls back to the primary and sees the write. This request took about
100 ms longer than a plain primary read.

### T+2.0s: with the token again

```
GET /api/queries/events/{id}   X-Read-After: <TOKEN>   ->  200, X-Served-By: primary   (replica still behind)
```

### T+3.1s: with the token, replica has caught up

```bash
sleep 3.1
curl -i $BASE/api/queries/events/$EVENT_ID -H "X-Read-After: $TOKEN"
```

```
HTTP/1.1 200
X-Served-By: replica
```

The replica has now replayed past the token, so the read goes there. The flip happens when the replica
catches up (about T+3s with this setup), not at a fixed time.

### T+6.0s: without a token

```
GET /api/queries/events/{id}   (no header)   ->  200, X-Served-By: replica
```

Anonymous reads also see the event now.

## Summary table

| T (s) | Call | Header sent | Replica at token? | Served by | Result |
|---|---|---|---|---|---|
| 0.0 | `POST /api/commands/events` | none | n/a | primary (write) | 201 + `X-Read-After` |
| 0.5 | `GET /api/queries/events/{id}` | none | n/a | **replica** | 404 (stale, lag 3s) |
| 0.5 | `GET /api/queries/events/{id}` | token | no (waits 100 ms) | **primary** | 200 |
| 2.0 | `GET /api/queries/events/{id}` | token | no (waits 100 ms) | **primary** | 200 |
| 3.1 | `GET /api/queries/events/{id}` | token | yes | **replica** | 200 |
| 6.0 | `GET /api/queries/events/{id}` | none | n/a | **replica** | 200 |

## A second write

Each command returns a **new** token, a later position.

```
T+10.0s  POST /api/commands/events/{id}/bookings      -> 201, X-Read-After: TOKEN2
T+10.5s  GET  /api/queries/bookings/{bookingId}  (TOKEN2)   -> primary, 200   (replica behind TOKEN2)
T+10.5s  GET  /api/queries/bookings/{bookingId}  (no token) -> replica, 404 (stale)
T+12.0s  GET  /api/queries/events/{id}           (TOKEN)    -> replica   (replica already past the old token)
T+12.0s  GET  /api/queries/events/{id}           (TOKEN2)   -> primary   (replica still behind TOKEN2)
T+13.1s  GET  /api/queries/bookings/{bookingId}  (TOKEN2)   -> replica, 200
```

The token is not tied to a resource: it means "everything up to this write". A read with TOKEN2 goes to the
primary until the replica has applied the booking, whatever it reads. A read with the old TOKEN does not care
about the booking.

## Changing the numbers

- **Replica lag 10s** (`scripts/set-replica-lag.sh 10s`): reads with the token stay on the primary for about
  10s and always return 200. With the 5s window approach the same lag returns a stale 404 at T+6s, so this is
  the main difference between the approaches.
- **Replica lag 0** (`scripts/set-replica-lag.sh 0`): the replica reaches the token within milliseconds, so
  token reads usually go to the replica after at most one 10 ms re-check.
- **Wait timeout**: `LSN_WAIT_TIMEOUT=0` never waits, so a behind replica sends the read straight to the
  primary. A larger value (say `300ms`) keeps more reads on the replica when lag is a few tens of milliseconds,
  but each such request can take that much longer, and it holds a request thread while it waits.
- **Replica stopped**: the check fails, the read goes to the primary (a warning is logged), and nothing is
  returned stale.

## Scripted version

Prints `X-Served-By` for each read so you can watch the flip from primary to replica:

```bash
BASE=http://localhost:8080
resp=$(curl -s -i -X POST $BASE/api/commands/events -H 'Content-Type: application/json' -d '{
  "name":"Demo","city":"Berlin","startsAt":"2027-01-01T20:00:00Z",
  "tiers":[{"name":"General","priceCents":5000,"quantity":100}]}' | tr -d '\r')
TOKEN=$(grep -i '^x-read-after:' <<<"$resp" | cut -d' ' -f2)
ID=$(tail -n1 <<<"$resp" | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "token: $TOKEN"

probe() {   # probe <label> [curl args]
  local label=$1; shift
  curl -s -o /dev/null -D - -w '%{http_code}' "$@" $BASE/api/queries/events/$ID | tr -d '\r' \
    | awk -v l="$label" 'tolower($1)=="x-served-by:"{s=$2} END{printf "%-28s HTTP %s  served-by=%s\n", l, $0, s}'
}

for i in 1 2 3 4 5 6 7; do
  echo "t=${i}s"
  probe "  with token"    -H "X-Read-After: $TOKEN"
  probe "  without token"
  sleep 1
done
```

Expected with the 3s replica lag: "with token" says `primary` for the first ~3 reads and `replica` after that, always
with 200. "without token" is always `replica`, returning 404 for about the first 3s and 200 after that. (The awk
`HTTP` column prints the last line of curl's output, which is the status code written by `-w`.)

## Caching interaction

Only reads routed to the **primary** bypass Redis. In this approach a token read that finds the replica caught
up is routed to the **replica**, so it goes through the cache like an anonymous read.

The LSN check proves the *database replica* has the write. It says nothing about the *cache*. Cache entries are
removed by the outbox, Debezium and Kafka path, which runs separately. So a token holder whose replica has
caught up can, in a narrow window, still read an old cached copy if the invalidation has not run yet. With a
replica that is seconds behind (as in the test setup) invalidation has long finished by then. With a replica
that is milliseconds behind, the window is real. The second invalidation (`app.cache.second-invalidation-delay`,
which still follows `READ_AFTER_WINDOW`) cleans up entries cached from a lagging replica, but it does not close
this window. If strict read-your-writes through the cache matters, route token reads around the cache as well.

## Trade-offs of this approach

- Accurate: the decision is based on what the replica has actually applied, so it is right for any lag, and
  a lag spike causes primary reads instead of stale ones.
- Reads that can be served by the replica are, as soon as it catches up, instead of waiting for a window to end.
- Costs: one extra `SELECT` on the primary per write response, and one on the replica per token read (plus the
  short polling when it is behind).
- PostgreSQL-specific (WAL positions). The primary's position needs a connection to the primary at write time.
- Does not by itself cover the cache (see above).

The simpler fixed-window alternative is in [read-your-writes-5s.md](read-your-writes-5s.md).
