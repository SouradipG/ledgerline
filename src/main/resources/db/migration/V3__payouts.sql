-- Money waiting on the payment provider sits in a clearing account per currency,
-- so it is visible on the books while the outcome is unknown.
alter table accounts drop constraint accounts_kind_check;
alter table accounts add constraint accounts_kind_check
    check (kind in ('CUSTOMER', 'TREASURY', 'PAYOUT_CLEARING'));

insert into accounts (kind, currency) values
    ('PAYOUT_CLEARING', 'USD'), ('PAYOUT_CLEARING', 'EUR'), ('PAYOUT_CLEARING', 'GBP'), ('PAYOUT_CLEARING', 'INR');
insert into balances (account_id, currency)
select id, currency from accounts where kind = 'PAYOUT_CLEARING';

-- RESERVED      funds moved to clearing, provider not called yet
-- UNKNOWN       provider call timed out or failed; outcome must be looked up before any retry
-- COMPLETED     provider settled; funds moved from clearing to treasury (left the ledger)
-- FAILED        provider declined; funds returned to the customer
-- NEEDS_REVIEW  still unknown after max attempts; a human has to look
create table payouts (
    id                  uuid primary key,
    idempotency_key     text        not null unique,
    account_id          bigint      not null references accounts (id),
    amount_minor        bigint      not null check (amount_minor > 0),
    currency            char(3)     not null,
    status              text        not null
        check (status in ('RESERVED', 'UNKNOWN', 'COMPLETED', 'FAILED', 'NEEDS_REVIEW')),
    attempts            int         not null default 0,
    next_attempt_at     timestamptz not null default now(),
    provider_reference  text,
    last_error          text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create index payouts_due_idx on payouts (next_attempt_at) where status in ('RESERVED', 'UNKNOWN');

create table recon_exceptions (
    id           bigint generated always as identity primary key,
    payment_id   text        not null,
    kind         text        not null,
    details      text        not null,
    detected_at  timestamptz not null default now(),
    unique (payment_id, kind)
);
