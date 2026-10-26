package dev.souradip.ledgerline.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.souradip.ledgerline.IntegrationTestBase;
import dev.souradip.ledgerline.activity.AccountActivityProjector;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.TransferRequest;
import dev.souradip.ledgerline.transfer.TransferService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxTest extends IntegrationTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    OutboxRelay relay;

    @Autowired
    AccountActivityProjector projector;

    @AfterEach
    void restoreRelay() {
        relay.afterSend = () -> {};
    }

    @Test
    void everyPostedTransferReachesTheReadModel() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        for (int i = 0; i < 50; i++) {
            transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(from, to, 3L, "USD"), LockingMode.PESSIMISTIC);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(activity(to, "credits_count")).isEqualTo(50);
            assertThat(activity(to, "credited_minor")).isEqualTo(150);
            assertThat(activity(from, "debits_count")).isEqualTo(50);
        });
    }

    /**
     * The relay dies after Kafka acknowledged a batch but before it marked the rows published.
     * The rows go out again on the next tick, so the consumer sees duplicates and must apply
     * each event once.
     */
    @Test
    void relayCrashAfterSendCausesRedeliveryButNoDoubleCounting() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        long duplicatesBefore = projector.duplicatesSkipped();

        AtomicBoolean crashed = new AtomicBoolean();
        relay.afterSend = () -> {
            if (crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated crash between publish and commit");
            }
        };
        for (int i = 0; i < 20; i++) {
            transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(from, to, 5L, "USD"), LockingMode.CONDITIONAL);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(crashed).isTrue();
            assertThat(projector.duplicatesSkipped()).isGreaterThan(duplicatesBefore);
            assertThat(activity(to, "credits_count")).isEqualTo(20);
            assertThat(activity(to, "credited_minor")).isEqualTo(100);
        });
        assertThat(jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single()).isZero();
    }

    @Test
    void aRejectedTransferWritesNoEvent() {
        long from = ledger.openAccount(10);
        long to = ledger.openAccount(0);
        long before = jdbc.sql("select count(*) from outbox").query(Long.class).single();

        var response = transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(from, to, 500L, "USD"), LockingMode.CONDITIONAL);

        assertThat(response.status()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from outbox").query(Long.class).single()).isEqualTo(before);
    }

    private long activity(long accountId, String column) {
        return jdbc.sql("select coalesce(max(" + column + "), 0) from account_activity where account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }
}
