-- Accounts are immutable metadata. Money lives in postings; balances is a
-- snapshot kept in step with postings inside the same transaction.
create table accounts (
    id          bigint generated always as identity primary key,
    kind        text        not null check (kind in ('CUSTOMER', 'TREASURY')),
    currency    char(3)     not null,
    created_at  timestamptz not null default now()
);

-- One row per account. allow_negative is true only for treasury accounts,
-- which are where money enters and leaves the ledger.
create table balances (
    account_id     bigint  primary key references accounts (id),
    currency       char(3) not null,
    balance_minor  bigint  not null default 0,
    allow_negative boolean not null default false,
    version        bigint  not null default 0,
    constraint balance_not_negative check (allow_negative or balance_minor >= 0)
);

create table transfers (
    id               uuid primary key,
    idempotency_key  text        not null unique,
    from_account_id  bigint      not null references accounts (id),
    to_account_id    bigint      not null references accounts (id),
    amount_minor     bigint      not null check (amount_minor > 0),
    currency         char(3)     not null,
    status           text        not null,
    created_at       timestamptz not null default now(),
    constraint distinct_accounts check (from_account_id <> to_account_id)
);

-- Append only. Every transfer writes exactly one negative and one positive row.
-- Ordering uses the sequence, never created_at, because wall clocks across
-- instances disagree.
create table postings (
    id            bigint generated always as identity primary key,
    transfer_id   uuid        not null references transfers (id),
    account_id    bigint      not null references accounts (id),
    amount_minor  bigint      not null check (amount_minor <> 0),
    currency      char(3)     not null,
    created_at    timestamptz not null default now()
);

create index postings_account_idx on postings (account_id, id);

create table idempotency_keys (
    key            text primary key,
    request_hash   text        not null,
    status         text        not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_code  int,
    response_body  jsonb,
    created_at     timestamptz not null default now(),
    locked_at      timestamptz not null default now()
);

-- One treasury account per supported currency, used to fund customer accounts.
insert into accounts (kind, currency) values ('TREASURY', 'USD'), ('TREASURY', 'EUR'), ('TREASURY', 'GBP'), ('TREASURY', 'INR');
insert into balances (account_id, currency, allow_negative)
select id, currency, true from accounts where kind = 'TREASURY';
