# X-Read-After walkthrough: which reads hit the primary, which hit the replica

This doc walks through a sequence of API calls and shows, for each read, whether it is served by the
**primary** or the **replica**, and why. It also shows the 5 second default window flipping a read from
primary to replica.

## The rules (from `ReadSourceResolver`)

| Request | Routed to |
|---|---|
| No `X-Read-After` header (or blank) | **replica** |
| Token age **< 5s** (`app.consistency.read-after-window`, env `READ_AFTER_WINDOW`) | **primary** |
| Token age **>= 5s** | **replica** |
| Token in the future (clock skew, negative age) | **primary** (treated as "just written") |
| Token not a number | **400** (`InvalidReadAfterTokenException`) |

- Token = commit time in epoch millis, returned by every command in the `X-Read-After` response header.
- Token age = `now - token` at the moment the read request arrives.
- The routing result is reported on every query response in the `X-Served-By` header (`primary` / `replica`).
- The window is strict: age of exactly 5000 ms already goes to the replica.
- Test setup: the replica applies WAL with an artificial delay of **3s** (`REPLICA_APPLY_DELAY`), so for
  3 seconds after a write the replica does not have the row yet. The 5s window is deliberately larger than
  this lag, so by the time the token expires the replica has caught up.

## Setup

```bash
docker compose -f infra/docker-compose.yml up -d
BASE=http://localhost:8080
```

## Timeline

`T` is seconds since the event was created. Token values below are illustrative; yours will differ.

### T+0.0s: create an event (command, always the primary)

```bash
curl -i -X POST $BASE/api/commands/events -H 'Content-Type: application/json' -d '{
  "name": "Demo Concert", "city": "Berlin", "startsAt": "2027-01-01T20:00:00Z",
  "tiers": [{"name": "General", "priceCents": 5000, "quantity": 100}]}'
```

```
HTTP/1.1 201
Location: /api/queries/events/6f1c...
X-Read-After: 1790000000000        <-- token = commit time (epoch ms)
{"id":"6f1c..."}
```

```bash
EVENT_ID=6f1c...
TOKEN=1790000000000
```

### T+0.5s: read WITHOUT a token

```bash
curl -i $BASE/api/queries/events/$EVENT_ID
```

```
HTTP/1.1 404
X-Served-By: replica
```

No token, so the replica is used. The replica is 3s behind, so the event does not exist there yet: a stale
read (404). This is the problem the header solves.

### T+0.5s: read WITH the token

```bash
curl -i $BASE/api/queries/events/$EVENT_ID -H "X-Read-After: $TOKEN"
```

```
HTTP/1.1 200
X-Served-By: primary
```

Token age is ~500 ms, which is below 5s, so the read is pinned to the primary and sees the write.

### T+2.0s: with the token again

```
GET /api/queries/events/{id}   X-Read-After: <TOKEN>   ->  200, X-Served-By: primary   (age ~2.0s < 5s)
```

Still inside the window. The replica is also still behind (3s lag), so the primary is the only correct answer.

### T+4.9s: with the token, just inside the window

```
GET /api/queries/events/{id}   X-Read-After: <TOKEN>   ->  200, X-Served-By: primary   (age ~4.9s < 5s)
```

Last read that is pinned to the primary.

### T+5.0s and later: with the token, window expired

```bash
sleep 5.1
curl -i $BASE/api/queries/events/$EVENT_ID -H "X-Read-After: $TOKEN"
```

```
HTTP/1.1 200
X-Served-By: replica
```

Token age >= 5s, so the token is ignored for routing and the replica serves it. The data is there because
the 3s lag is shorter than the 5s window.

### T+6.0s: without a token

```
GET /api/queries/events/{id}   (no header)   ->  200, X-Served-By: replica
```

Replica has caught up, so anonymous reads now see the event too.

## Summary table

| T (s) | Call | Header sent | Token age | Served by | Result |
|---|---|---|---|---|---|
| 0.0 | `POST /api/commands/events` | none | n/a | primary (write) | 201 + `X-Read-After` |
| 0.5 | `GET /api/queries/events/{id}` | none | n/a | **replica** | 404 (stale, lag 3s) |
| 0.5 | `GET /api/queries/events/{id}` | token | 0.5s | **primary** | 200 |
| 2.0 | `GET /api/queries/events/{id}` | token | 2.0s | **primary** | 200 |
| 4.9 | `GET /api/queries/events/{id}` | token | 4.9s | **primary** | 200 |
| 5.1 | `GET /api/queries/events/{id}` | token | 5.1s | **replica** | 200 (replica caught up at 3s) |
| 6.0 | `GET /api/queries/events/{id}` | none | n/a | **replica** | 200 |

## A second write resets the window

Each command returns a **new** token, so the pinning restarts from the latest write.

```
T+10.0s  POST /api/commands/events/{id}/bookings      -> 201, X-Read-After: TOKEN2
T+10.5s  GET  /api/queries/bookings/{bookingId}  (TOKEN2)   -> primary, 200
T+10.5s  GET  /api/queries/bookings/{bookingId}  (no token) -> replica, 404 (stale)
T+12.0s  GET  /api/queries/events/{id}           (TOKEN)    -> replica   (old token, age 12s)
T+12.0s  GET  /api/queries/events/{id}           (TOKEN2)   -> primary   (age 2s)
T+15.1s  GET  /api/queries/bookings/{bookingId}  (TOKEN2)   -> replica, 200
```

The token is not tied to a resource. TOKEN2 pins every read the client makes within 5s of the booking, not
only the booking read. The client should send the most recent token it has received.

## Scripted version

Prints `X-Served-By` for each read so you can watch the flips:

```bash
BASE=http://localhost:8080
resp=$(curl -s -i -X POST $BASE/api/commands/events -H 'Content-Type: application/json' -d '{
  "name":"Demo","city":"Berlin","startsAt":"2027-01-01T20:00:00Z",
  "tiers":[{"name":"General","priceCents":5000,"quantity":100}]}' | tr -d '\r')
TOKEN=$(grep -i '^x-read-after:' <<<"$resp" | cut -d' ' -f2)
ID=$(tail -n1 <<<"$resp" | sed -E 's/.*"id":"([^"]+)".*/\1/')

probe() {   # probe <label> [curl args]
  local label=$1; shift
  curl -s -o /dev/null -D - -w '%{http_code}' "$@" $BASE/api/queries/events/$ID | tr -d '\r' \
    | awk -v l="$label" 'tolower($1)=="x-served-by:"{s=$2} END{printf "%-28s HTTP %s  served-by=%s\n", l, $0, s}'
}

for i in 1 2 3 4 5 6 7; do
  age=$(( $(date +%s%3N) - TOKEN ))
  echo "age=${age}ms"
  probe "  with token"    -H "X-Read-After: $TOKEN"
  probe "  without token"
  sleep 1
done
```

Expected: "with token" says `primary` while age < 5000 ms and `replica` afterwards. "without token" is always
`replica`, returning 404 for about the first 3s and 200 after that. (The awk `HTTP` column prints the last
line of curl's output, which is the status code written by `-w`.)

## Changing the numbers

- Replica lag: `scripts/set-replica-lag.sh 10s`. With lag 10s and the default 5s window, a read at T+6s
  with the token goes to the replica and returns a stale 404. This is what happens when the window is
  smaller than the real replication lag.
- Window: set `READ_AFTER_WINDOW=15s` and restart the app to pin for longer.
- Rule of thumb: window > worst-case replication lag, otherwise users can see their own writes disappear.
