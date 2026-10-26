# Ledgerline

[![ci](https://github.com/SouradipG/ledgerline/actions/workflows/ci.yml/badge.svg)](https://github.com/SouradipG/ledgerline/actions/workflows/ci.yml)

A double entry payments ledger that stays correct when two transfers hit the same account in the same millisecond, when clients retry, and when an instance dies halfway through a request.

Java 25, Spring Boot 4, PostgreSQL, Kafka.

## The problem

Networks fail after the server has already done the work. The client sees a timeout, retries, and the customer gets charged twice. This is why Stripe puts an `Idempotency-Key` on every write: the server remembers the first result for a key and returns that same result to every retry.

The second classic failure is the double spend. An account holds 100. Two requests each read the balance, both see 100, both decide 80 is fine, and both write. The account is now at minus 60, or one debit silently disappeared, depending on how the write was done.

Neither bug shows up when you click through the API by hand on your laptop. Both show up within minutes in production. Ledgerline is built to make them impossible, and the test suite proves it against a real Postgres under real concurrency.

## What it covers

### Correctness under concurrency

* **Double entry postings.** Every transfer writes exactly one negative and one positive posting. Balances are a snapshot updated in the same transaction, so `sum(postings) = 0` and every balance equals the sum of its postings, always.
* **Idempotency keys stored in Postgres.** Same key and same body returns the stored response byte for byte, with `Idempotent-Replayed: true`. Same key with a different body returns `422`. Same key while the first request is still running returns `409`.
* **Crash recovery for keys.** A key stuck in `IN_PROGRESS` past its lease (the instance that claimed it died) can be taken over by a retry. A unique constraint on `transfers.idempotency_key` backs this up in case the first owner was slow rather than dead.
* **Three locking strategies**, selectable with `ledger.locking`, so they can be compared under the same load.
* **A bulkhead** in front of database work, sized to the connection pool.
* **Money as integer minor units.** No `double` anywhere near an amount.

### Events and payouts

* **Transactional outbox.** Each transfer writes a `TransferPosted` event to an `outbox` table in the same transaction as its postings. A relay on every instance publishes to Kafka with `FOR UPDATE SKIP LOCKED`, so instances share the work instead of double publishing.
* **Idempotent consumer.** The `account_activity` read model records each event id in `processed_events` in the same transaction as the update. Redelivered events become no ops.
* **Payout saga** against a payment provider that can decline, go down, or take the money and then never answer. Funds sit in a clearing account while the outcome is open. A timeout is never treated as a failure: the payout goes to `UNKNOWN` and is resolved only by asking the provider.
* **Reconciliation** that compares the provider's settlements with the ledger and records any mismatch for a human, instead of quietly fixing money.

## Running it

```bash
docker compose up -d                                    # Postgres 18 and Kafka
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo  # demo also starts the simulated provider on :8089
```

Money enters the ledger through a treasury account per currency (created by the first migration). Fund a customer account by transferring from the treasury:

```bash
# open an account
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"currency":"USD"}'

# find the USD treasury account id
curl -s localhost:8080/accounts/treasury/USD

# move 10.00 USD into account 5
curl -s -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-5-first' \
  -d '{"fromAccountId":1,"toAccountId":5,"amountMinor":1000,"currency":"USD"}'
```

Run the same `curl` again and you get the identical response with `Idempotent-Replayed: true`, and the balance does not change.

Ask for a payout. The API answers `202 Accepted` straight away with status `RESERVED`, and a background worker talks to the provider:

```bash
curl -s -X POST localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: withdraw-5-1' \
  -d '{"accountId":5,"amountMinor":400,"currency":"USD"}'

curl -s localhost:8080/payouts/<id>    # COMPLETED a moment later
```

Tests need only Docker. Testcontainers starts Postgres and Kafka itself:

```bash
./mvnw verify
```

## Concurrency decisions

### Lock order

Every strategy touches the two balance rows in ascending account id order. If transfer X locked A then B while transfer Y locked B then A, each would hold one row and wait for the other. Postgres would detect the cycle and abort one of them with a deadlock error. A single global order makes the cycle impossible. `OpposingTransfersTest` fires 1,000 transfers in both directions between the same two accounts and expects zero deadlocks.

### Three ways to debit safely

| Strategy | How | Good at | Weak at |
|---|---|---|---|
| `PESSIMISTIC` | `SELECT ... FOR UPDATE` both rows, check, update | High contention. Never retries. | Holds locks during the check. |
| `OPTIMISTIC` | Read without locks, `UPDATE ... WHERE version = :seen`, retry on conflict with jittered backoff | Low contention. No locks while reading. | One hot account makes most attempts lose. |
| `CONDITIONAL` | `UPDATE ... SET balance = balance - :amt WHERE balance >= :amt` | One round trip, no retries. | The rule has to fit in a `WHERE` clause. |

The conditional update is safe under Postgres's default `READ COMMITTED` because when an `UPDATE` waits on a row another transaction is changing, Postgres re-evaluates the `WHERE` clause against the newly committed version before applying it.

`ConcurrentDebitTest` runs the double spend against each strategy: 200 concurrent debits of 80 against a balance of 100. Exactly one succeeds every time.

### Idempotency flow

```
claim key (own transaction, commits immediately)
   |
   +-- already COMPLETED, same body  -> return stored response
   +-- IN_PROGRESS, lease still valid -> 409
   +-- different body                -> 422
   |
move money + store response (one transaction)
   |
   +-- business rejection (e.g. insufficient funds) -> store the 422, replay it later
   +-- unexpected failure                           -> release key so the retry can run
```

The claim commits on its own so a concurrent duplicate sees it straight away. The stored response commits in the same transaction as the money movement, so there is no window where money moved but the key does not know about it.

### Virtual threads and a small pool

Virtual threads are on. That means the server will accept thousands of concurrent requests, while Hikari has 10 connections. Without a limit, the extra requests pile up inside the pool, every one of them holding an idempotency claim, until they hit the connection timeout. CPU looks idle and latency explodes.

`DbBulkhead` puts a semaphore with the same number of permits in front of database work. Requests queue there with a bounded wait and fail fast with `503` and `Retry-After` when the wait runs out. The fix is deliberately not a pool of 500: Postgres gets slower once active connections go well past the core count.

## Works locally, breaks in production

| Looks fine locally | What happens in production | What Ledgerline does |
|---|---|---|
| Processed keys in a `ConcurrentHashMap` | Three instances behind a load balancer. The retry lands on a different instance and runs again. | Keys live in Postgres with a primary key constraint deciding the race. |
| Virtual threads plus a default pool | Thousands of requests wait on 10 connections. Timeouts while the database sits idle. | Semaphore bulkhead sized to the pool, fast `503` when saturated. |
| Tests on H2 | H2's locking and isolation differ from Postgres. Concurrency tests pass and prove nothing. | Every test runs on Postgres 18 via Testcontainers. |
| Ordering events by `created_at` | Clocks on different instances drift. Events sort in the wrong order. | Postings are ordered by a database sequence. Timestamps are for humans. |
| Storing the replay body as `jsonb` | Looks like the same JSON, but `jsonb` reorders keys and drops whitespace. Clients that hash or sign the response see a different answer on retry. The first CI run caught this. | The body is stored as `text`, exactly as sent. |
| Commit, then publish to Kafka | A crash between the two loses the event forever. | Transactional outbox, at least once delivery, idempotent consumer. |
| Treat a provider timeout as a failure and refund | The provider had already paid. The customer is paid twice. | `UNKNOWN` state, status lookup before any retry or refund. |
| `@Scheduled` job and HTTP client defaults | Every instance runs the job, and a hung provider holds threads forever. | `SKIP LOCKED` claims with leases, explicit connect and read timeouts. |
| Retry on every failure | Retrying a timeout after the work committed duplicates it. | Retries are only safe because of the idempotency key, and only optimistic conflicts are retried internally. |

## Events: the dual write problem

The obvious way to tell other services about a transfer is to commit, then publish to Kafka. If the process dies between the two, the transfer happened and nobody downstream hears about it. Publishing first is worse: the event goes out and the transaction can still roll back.

```
same transaction:  postings + balances + outbox row
                              |
OutboxRelay (every instance): SELECT ... FOR UPDATE SKIP LOCKED  ->  Kafka (acks=all, idempotent producer)
                              |                                         |
                        mark published                        AccountActivityProjector
                                                              dedupe on event id + update, one transaction
```

Delivery is at least once on purpose. If the relay dies after Kafka acknowledged a batch but before it marked the rows published, the batch goes out again. `OutboxTest` injects exactly that crash and checks that the read model still counts every transfer once.

One trade off is written down rather than hidden: with several relays, two batches can reach Kafka out of id order. The projection here is a set of counters, so order does not matter. A consumer that needed strict per account order would need a single relay per partition.

## Payouts: when the other side may or may not have paid

```
POST /payouts  ->  customer -> clearing   RESERVED, 202 Accepted
                         |
PayoutWorker   ->  provider
                    settled          clearing -> treasury   COMPLETED
                    declined         clearing -> customer   FAILED (compensation)
                    timeout / 5xx    UNKNOWN, back off, then ask the provider before anything else
                    still unknown    NEEDS_REVIEW after max attempts, money stays in clearing
```

* **No inline provider call.** Calling the provider on the request thread would tie our latency and uptime to theirs. The request only reserves funds.
* **No locks across the network.** A worker claims due payouts with a lease in one short transaction (`SKIP LOCKED`, so several workers can poll), makes the HTTP call with no transaction open, then applies the outcome in another transaction guarded by `WHERE status IN ('RESERVED', 'UNKNOWN')`. Two workers can never both settle or both refund.
* **The provider dedupes on payment id**, so resubmitting after a lost response never charges twice.
* **Timeouts on every call.** The JDK HTTP client waits forever by default. Connect and read timeouts are set, and the read timeout is shorter than anything upstream.

`PaymentProviderSimulator` plays the provider and counts how many times each payment was actually executed. `PayoutSagaTest` covers settle, decline, outage then recovery, a provider that never recovers, eight workers polling at once, and the nasty one: the provider pays, then stalls past our timeout. The test asserts the customer is not refunded and the payment executed exactly once.

## Reconciliation

Retries and idempotency keys make double payment unlikely. Finance teams do not sign off on unlikely. `ReconciliationJob` runs nightly, pulls the provider's settlements and compares them with completed payouts. It records `SETTLED_AT_PROVIDER_ONLY`, `COMPLETED_IN_LEDGER_ONLY`, `SETTLED_BUT_NOT_COMPLETED` and `AMOUNT_MISMATCH` in `recon_exceptions`, once each, and never edits balances itself. Payouts still in flight are skipped.

## Load testing

`load/transfers.js` is a k6 script that funds a set of accounts and then fires random transfers at a fixed arrival rate, so slow responses cannot quietly reduce the load (the coordinated omission problem with closed loop tools). It fails the run if p99 latency or the error rate crosses a threshold:

```bash
k6 run -e BASE_URL=http://localhost:8080 -e RATE=200 load/transfers.js
```

After a run, check that nothing drifted under load. This must return 0:

```sql
select coalesce(sum(amount_minor), 0) from postings;
```

## What is next

* **Hot account handling.** Splitting one merchant account into sub accounts when a single row becomes the bottleneck, and measuring the difference.
* **Published load numbers** for the three locking strategies at low and high contention.
* **Network fault drills** with Toxiproxy between the app and Postgres and Kafka.
