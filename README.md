# Ledgerline

[![ci](https://github.com/SouradipG/ledgerline/actions/workflows/ci.yml/badge.svg)](https://github.com/SouradipG/ledgerline/actions/workflows/ci.yml)

A double entry payments ledger that stays correct when two transfers hit the same account in the same millisecond, when clients retry, and when an instance dies halfway through a request.

Java 25, Spring Boot 4, PostgreSQL. Kafka arrives in the next milestone.

## The problem

Networks fail after the server has already done the work. The client sees a timeout, retries, and the customer gets charged twice. This is why Stripe puts an `Idempotency-Key` on every write: the server remembers the first result for a key and returns that same result to every retry.

The second classic failure is the double spend. An account holds 100. Two requests each read the balance, both see 100, both decide 80 is fine, and both write. The account is now at minus 60, or one debit silently disappeared, depending on how the write was done.

Neither bug shows up when you click through the API by hand on your laptop. Both show up within minutes in production. Ledgerline is built to make them impossible, and the test suite proves it against a real Postgres under real concurrency.

## What milestone 1 covers

* **Double entry postings.** Every transfer writes exactly one negative and one positive posting. Balances are a snapshot updated in the same transaction, so `sum(postings) = 0` and every balance equals the sum of its postings, always.
* **Idempotency keys stored in Postgres.** Same key and same body returns the stored response byte for byte, with `Idempotent-Replayed: true`. Same key with a different body returns `422`. Same key while the first request is still running returns `409`.
* **Crash recovery for keys.** A key stuck in `IN_PROGRESS` past its lease (the instance that claimed it died) can be taken over by a retry. A unique constraint on `transfers.idempotency_key` backs this up in case the first owner was slow rather than dead.
* **Three locking strategies**, selectable with `ledger.locking`, so they can be compared under the same load.
* **A bulkhead** in front of database work, sized to the connection pool.
* **Money as integer minor units.** No `double` anywhere near an amount.

## Running it

```bash
docker compose up -d          # Postgres 18 on localhost:5432
./mvnw spring-boot:run
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

Tests need only Docker. Testcontainers starts Postgres itself:

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
| Retry on every failure | Retrying a timeout after the work committed duplicates it. | Retries are only safe because of the idempotency key, and only optimistic conflicts are retried internally. |

## Next milestones

* **Transactional outbox to Kafka.** Today, a transfer that commits and then crashes before publishing an event would leave downstream systems unaware. The outbox writes the event in the same transaction, and a relay using `FOR UPDATE SKIP LOCKED` publishes it, so several instances can run the relay without double publishing.
* **Idempotent consumers** that dedupe on event id, because Kafka delivers at least once.
* **Payout saga against a simulated payment provider** that can be slow, fail, or accept and then time out, with an explicit unknown outcome state that checks status before retrying.
* **Reconciliation job** that compares the ledger against the provider's settlement file.
* **Hot account handling.** Splitting one merchant account into sub accounts when a single row becomes the bottleneck.
* **Drills.** k6 load tests with published percentiles, Toxiproxy latency and network cuts, and kill tests between commit and publish.
