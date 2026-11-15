#!/bin/bash
# Shows the Redis cache, outbox + Debezium + Kafka invalidation, and the read-after-write bypass.
# Requires the full stack (scripts/start.sh) with the default 3s replica lag. Works in both consistency modes
# (lsn, the default, and time); the 5s second-invalidation delay applies in both.
set -e
BASE="${BASE:-http://localhost:8080}"

# get <label> <path> [curl args]: prints the serving tier and the available tickets of the first tier.
get() {
    local label="$1" path="$2"; shift 2
    local out served avail
    out=$(curl -s -D - "$@" "$BASE$path" | tr -d '\r')
    served=$(grep -i '^x-served-by:' <<<"$out" | cut -d' ' -f2)
    avail=$(grep -o '"available":[0-9]*' <<<"$out" | head -1 | cut -d: -f2)
    printf '%-58s -> %-8s available=%s\n' "$label" "${served:-n/a}" "${avail:--}"
}

echo "== Create an event and wait until the token expired and the replica caught up (6s)"
resp=$(curl -s -i -X POST "$BASE/api/commands/events" -H 'Content-Type: application/json' -d '{
  "name": "Cache Demo", "city": "Berlin", "startsAt": "2027-01-01T20:00:00Z",
  "tiers": [{"name": "General", "priceCents": 5000, "quantity": 100}]}' | tr -d '\r')
event_id=$(tail -n1 <<<"$resp" | sed -E 's/.*"id":"([^"]+)".*/\1/')
sleep 6
echo
echo "== Anonymous reads: first fills Redis from the replica, the rest are served from Redis"
get "1st read, no token (cache miss)"  "/api/queries/events/$event_id"
get "2nd read, no token (cache hit)"   "/api/queries/events/$event_id"
echo
echo "== Book 2 tickets (writes the outbox row; Debezium -> Kafka -> cache invalidation)"
tier_id=$(curl -s "$BASE/api/queries/events/$event_id" | grep -o '"tierId":"[^"]*"' | head -1 | cut -d'"' -f4)
resp=$(curl -s -i -X POST "$BASE/api/commands/events/$event_id/bookings" -H 'Content-Type: application/json' \
    -d "{\"tierId\": \"$tier_id\", \"quantity\": 2, \"customerEmail\": \"a@example.com\"}" | tr -d '\r')
token=$(grep -i '^x-read-after:' <<<"$resp" | cut -d' ' -f2)
sleep 1
get "with token, 1s later (bypasses Redis)"         "/api/queries/events/$event_id" -H "X-Read-After: $token"
get "no token, 1s later (invalidated, replica 3s behind)" "/api/queries/events/$event_id"
get "no token again (stale value was cached from replica)" "/api/queries/events/$event_id"
echo "   ^ the replica was still behind, so a stale value got cached. The second invalidation fixes it."
sleep 5
echo
echo "== 6s after the write: second invalidation (at 5s) removed the stale entry, replica has caught up"
get "no token (miss, fresh from replica)" "/api/queries/events/$event_id"
get "no token (hit)"                      "/api/queries/events/$event_id"
