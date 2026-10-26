package dev.souradip.ledgerline;

import dev.souradip.ledgerline.locking.LockingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger")
public record LedgerProperties(
        LockingMode locking,
        Optimistic optimistic,
        Bulkhead bulkhead,
        Idempotency idempotency,
        Outbox outbox,
        Payouts payouts,
        Provider provider) {

    public record Optimistic(int maxAttempts) {}

    public record Bulkhead(int permits, Duration maxWait) {}

    public record Idempotency(Duration lease) {}

    public record Payouts(boolean workerEnabled, Duration lease, int maxAttempts, Duration retryBase, Duration retryMax) {}

    public record Provider(String baseUrl, Duration connectTimeout, Duration readTimeout) {}

    public record Outbox(boolean relayEnabled, String topic, int batchSize, Duration sendTimeout) {}
}
