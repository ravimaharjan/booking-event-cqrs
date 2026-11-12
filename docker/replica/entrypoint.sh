#!/bin/bash
# Bootstraps a streaming replica from the primary on first start, then runs Postgres as a hot standby.
set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "Initialising replica from primary..."
    pg_basebackup -d "host=primary user=replicator password=replicator" \
        -D "$PGDATA" -R -X stream -S replica_1 -P
    chmod 700 "$PGDATA"
    echo "recovery_min_apply_delay = '${REPLICA_APPLY_DELAY:-0}'" >> "$PGDATA/postgresql.auto.conf"
fi

exec docker-entrypoint.sh postgres -c hot_standby=on
