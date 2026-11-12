#!/bin/bash
# Runs once on first start of the primary: creates the replication user and slot.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    create role replicator with replication login password 'replicator';
    select pg_create_physical_replication_slot('replica_1');
EOSQL

echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
