package dev.souradip.ledgerline.concurrency;

import dev.souradip.ledgerline.LedgerProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Caps how many requests can be doing database work at once.
 *
 * <p>With virtual threads the server will happily accept thousands of concurrent requests, but
 * the connection pool only has a handful of connections. Without a cap every extra request sits
 * inside Hikari waiting for a connection, holding its idempotency claim and timing out at the
 * pool's connection timeout, while the database itself looks idle. The semaphore makes that
 * queue explicit and bounded, and lets us fail fast with a 503 and Retry-After instead.
 *
 * <p>The fix is deliberately not "raise the pool to 500". Postgres gets slower, not faster, once
 * active connections go well past the core count.
 */
@Component
public class DbBulkhead {

    private final Semaphore permits;
    private final long maxWaitNanos;

    public DbBulkhead(LedgerProperties properties) {
        this.permits = new Semaphore(properties.bulkhead().permits(), true);
        this.maxWaitNanos = properties.bulkhead().maxWait().toNanos();
    }

    public <T> T call(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BulkheadFullException("interrupted while waiting for a database slot");
        }
        if (!acquired) {
            throw new BulkheadFullException("too many concurrent requests, retry shortly");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
