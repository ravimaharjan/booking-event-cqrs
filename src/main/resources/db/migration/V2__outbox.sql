-- Transactional outbox. A row is inserted (and deleted again) in the same transaction as the business
-- change. Debezium reads the insert from the WAL and publishes it to Kafka, so the row never needs to
-- stay in the table. Column names are mapped in docker/debezium/outbox-connector.json.
create table outbox (
    id             uuid primary key,
    aggregate_type varchar(100) not null,
    aggregate_id   varchar(100) not null,
    type           varchar(100) not null,
    payload        jsonb        not null,
    created_at     timestamptz  not null default now()
);
