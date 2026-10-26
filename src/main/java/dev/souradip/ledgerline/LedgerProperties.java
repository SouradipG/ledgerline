package dev.souradip.ledgerline;

import dev.souradip.ledgerline.locking.LockingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger")
public record LedgerProperties(
        LockingMode locking,
        Optimistic optimistic,
        Bulkhead bulkhead,
        Idempotency idempotency) {

    public record Optimistic(int maxAttempts) {}

    public record Bulkhead(int permits, Duration maxWait) {}

    public record Idempotency(Duration lease) {}
}
