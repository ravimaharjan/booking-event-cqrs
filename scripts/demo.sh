#!/bin/bash
# Shows the stale-read problem and the read-after-write fix. Requires `docker compose -f infra/docker-compose.yml up` and a replica lag > 0
# (default 3s). Needs only curl.
set -e
BASE="${BASE:-http://localhost:8080}"

# Sends a request and prints the status line plus the X-Served-By header.
get() {
    local label="$1" url="$2"; shift 2
    local out
    out=$(curl -s -o /dev/null -D - -w '%{http_code}' "$@" "$url" | tr -d '\r')
    local code served
    code=$(tail -n1 <<<"$out")
    served=$(grep -i '^x-served-by:' <<<"$out" | cut -d' ' -f2 || true)
    printf '%-52s -> HTTP %s (served by %s)\n' "$label" "$code" "${served:-n/a}"
}

echo "== Create an event (command, hits the primary)"
resp=$(curl -s -i -X POST "$BASE/api/commands/events" -H 'Content-Type: application/json' -d '{
  "name": "Demo Concert", "city": "Berlin", "startsAt": "2027-01-01T20:00:00Z",
  "tiers": [{"name": "General", "priceCents": 5000, "quantity": 100}]}' | tr -d '\r')
token=$(grep -i '^x-read-after:' <<<"$resp" | cut -d' ' -f2)
event_id=$(tail -n1 <<<"$resp" | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "event id: $event_id   read-after token: $token"
echo

echo "== Immediately read it back"
get "without token (replica, may be stale)" "$BASE/api/queries/events/$event_id"
get "with X-Read-After token (primary)"     "$BASE/api/queries/events/$event_id" -H "X-Read-After: $token"
echo

echo "== Book 2 tickets, then read the booking"
tier_id=$(curl -s -H "X-Read-After: $token" "$BASE/api/queries/events/$event_id" | grep -o '"tierId":"[^"]*"' | head -1 | cut -d'"' -f4)
resp=$(curl -s -i -X POST "$BASE/api/commands/events/$event_id/bookings" -H 'Content-Type: application/json' \
    -d "{\"tierId\": \"$tier_id\", \"quantity\": 2, \"customerEmail\": \"a@example.com\"}" | tr -d '\r')
btoken=$(grep -i '^x-read-after:' <<<"$resp" | cut -d' ' -f2)
booking_id=$(tail -n1 <<<"$resp" | sed -E 's/.*"id":"([^"]+)".*/\1/')
get "booking without token (replica, may be stale)" "$BASE/api/queries/bookings/$booking_id"
get "booking with token (primary)"                  "$BASE/api/queries/bookings/$booking_id" -H "X-Read-After: $btoken"
echo

echo "== Wait for the replica to catch up, then read without a token"
sleep 6
get "booking without token (replica, caught up)"    "$BASE/api/queries/bookings/$booking_id"
get "city listing without token (replica)"          "$BASE/api/queries/events?city=Berlin"
