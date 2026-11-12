-- Write model (normalized)
create table events (
    id         uuid primary key,
    name       varchar(200) not null,
    city       varchar(100) not null,
    starts_at  timestamptz  not null,
    created_at timestamptz  not null default now()
);

create table ticket_tiers (
    id          uuid primary key,
    event_id    uuid         not null references events (id),
    name        varchar(100) not null,
    price_cents bigint       not null check (price_cents >= 0),
    total       int          not null check (total > 0),
    available   int          not null,
    check (available >= 0 and available <= total)
);
create index idx_ticket_tiers_event on ticket_tiers (event_id);

create table bookings (
    id             uuid primary key,
    event_id       uuid         not null references events (id),
    tier_id        uuid         not null references ticket_tiers (id),
    quantity       int          not null check (quantity > 0),
    customer_email varchar(255) not null,
    created_at     timestamptz  not null default now()
);
create index idx_bookings_event on bookings (event_id);

-- Read model (denormalized). Maintained in the same transaction as the write model,
-- so replication delivers both to the replicas atomically.
create table event_listing (
    event_id          uuid primary key references events (id),
    name              varchar(200) not null,
    city              varchar(100) not null,
    starts_at         timestamptz  not null,
    min_price_cents   bigint       not null,
    available_tickets int          not null,
    updated_at        timestamptz  not null default now()
);
create index idx_event_listing_city_start on event_listing (city, starts_at, event_id);
