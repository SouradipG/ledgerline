-- Events are written here in the same transaction as the postings they describe,
-- then published to Kafka by OutboxRelay. Either both the postings and the event
-- exist, or neither does.
create table outbox (
    id            bigint generated always as identity primary key,
    event_id      uuid        not null unique,
    event_type    text        not null,
    message_key   text        not null,
    payload       text        not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz
);

create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- Consumers record every event id they have applied, inside the same transaction
-- as the change, so a redelivered event becomes a no op.
create table processed_events (
    consumer      text        not null,
    event_id      uuid        not null,
    processed_at  timestamptz not null default now(),
    primary key (consumer, event_id)
);

-- A read model built from the transfer events: per account money in and out.
create table account_activity (
    account_id     bigint primary key,
    credits_count  bigint not null default 0,
    debits_count   bigint not null default 0,
    credited_minor bigint not null default 0,
    debited_minor  bigint not null default 0
);
