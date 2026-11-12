#!/bin/bash
# Usage: scripts/set-replica-lag.sh 5s   (use 0 to disable the artificial delay)
set -e
DELAY="${1:?usage: $0 <delay, e.g. 3s | 0>}"

docker compose exec -T replica psql -U booking -d booking \
    -c "alter system set recovery_min_apply_delay = '$DELAY'" \
    -c "select pg_reload_conf()" \
    -c "show recovery_min_apply_delay"
