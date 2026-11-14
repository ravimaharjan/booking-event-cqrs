#!/bin/bash
# Steps a constant request rate up through several levels and prints what the system actually achieved.
# A level PASSES when it delivers >= 97% of the target rate, < 1% errors and p95 < 200 ms.
# Usage: scripts/capacity-test.sh                       hot key, default levels
#        TARGET=mixed scripts/capacity-test.sh          many keys + listings
#        BASE_URL=http://localhost:8088 scripts/capacity-test.sh     through the caching proxy
#        LEVELS="2000 5000 10000" DURATION=30s scripts/capacity-test.sh
# Requires k6 and a running stack (scripts/start.sh). The load generator runs on THIS machine next to the app, so
# the result is a floor for the app, not a ceiling: run k6 from another host for a truer number.
set -e
cd "$(dirname "$0")/.."

LEVELS="${LEVELS:-2000 4000 6000 8000 12000}"
DURATION="${DURATION:-30s}"
TARGET="${TARGET:-hot}"
BASE_URL="${BASE_URL:-http://localhost:8080}"   # http://localhost:8088 goes through the caching proxy
SECONDS_PER_LEVEL="${DURATION%s}"
OUT="$(mktemp -d)"

replica_tx() {
    docker compose -f infra/docker-compose.yml exec -T replica psql -tA -U booking -d booking \
        -c "select xact_commit from pg_stat_database where datname='booking'" 2>/dev/null || echo 0
}

printf "%-8s %-10s %-9s %-9s %-9s %-9s %-14s %s\n" target_rps achieved p95_ms p99_ms errors dropped replica_xacts result
SAMPLES="$OUT/resources.jsonl"
RESOURCE_ROWS=""
for rate in $LEVELS; do
    before=$(replica_tx)
    scripts/resource-sampler.py sample "$SAMPLES" &
    sampler=$!
    t_start=$(date +%s.%N)
    k6 run -q -e BASE_URL="$BASE_URL" -e RATE="$rate" -e DURATION="$DURATION" -e TARGET="$TARGET" \
        --summary-export "$OUT/$rate.json" k6/capacity-test.js >"$OUT/$rate.log" 2>&1 || true
    t_end=$(date +%s.%N)
    kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null || true
    after=$(replica_tx)
    # Skip the first 10s of the run (seeding events and waiting for the replica); keep the load window.
    RESOURCE_ROWS="$RESOURCE_ROWS$(printf '%-8s ' "$rate")$(scripts/resource-sampler.py summary "$SAMPLES" "$(echo "$t_start + 10" | bc)" "$t_end")"$'\n'
    python3 - "$OUT/$rate.json" "$rate" "$SECONDS_PER_LEVEL" "$((after - before))" <<'PY'
import json, sys
path, rate, secs, tx = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
m = json.load(open(path))["metrics"]
planned = rate * secs
dropped = m.get("dropped_iterations", {}).get("count", 0)
achieved = rate * (1 - dropped / planned)
p95 = m["http_req_duration"]["p(95)"]
p99 = m["http_req_duration"]["p(99)"]
failed = m["http_req_failed"].get("value", m["http_req_failed"].get("rate", 0))
ok = achieved >= 0.97 * rate and failed < 0.01 and p95 < 200
print(f"{rate:<8} {achieved:<10.0f} {p95:<9.1f} {p99:<9.1f} {failed*100:<8.2f}% {dropped:<9} {tx:<14} {'PASS' if ok else 'FAIL'}")
PY
done
echo
echo "Resources during the load window. CPU in cores (average/peak, 1.0 = one busy core), memory in MiB (peak)."
printf "%-8s %-12s %-12s %-12s %-12s %-12s %-12s %-12s %-12s %-12s %-12s\n" target_rps "app_cpu" "app_mem" "redis_cpu" "redis_mem" "replica_cpu" "primary_cpu" "edge_cpu" "k6_cpu" "dproxy_cpu" "host_cpu"
printf "%s" "$RESOURCE_ROWS"
echo "Host: $(nproc) cores, $(free -m | awk '/Mem:/ {print $2}') MiB RAM. App container has no CPU or memory limit."
echo "k6 output per level: $OUT"
