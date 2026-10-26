package dev.souradip.ledgerline.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.souradip.ledgerline.IntegrationTestBase;
import dev.souradip.ledgerline.payout.PayoutRequest;
import dev.souradip.ledgerline.payout.PayoutService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationTest extends IntegrationTestBase {

    @Autowired
    ReconciliationJob job;

    @Autowired
    PayoutService payouts;

    @Test
    void matchingBooksProduceNoExceptions() {
        long account = ledger.openAccount(1_000);
        payouts.request(UUID.randomUUID().toString(), new PayoutRequest(account, 300L, "USD"));
        awaitAllPayoutsFinal();

        assertThat(job.run()).isZero();
        assertThat(job.exceptions()).isEmpty();
    }

    @Test
    void settlementTheLedgerNeverSawIsFlagged() {
        PROVIDER.recordSettlement("ghost-payment", 9_999, "USD");

        assertThat(job.run()).isEqualTo(1);
        assertThat(job.exceptions()).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo("SETTLED_AT_PROVIDER_ONLY"));
    }

    @Test
    void completedPayoutMissingAtTheProviderIsFlaggedOnce() {
        long account = ledger.openAccount(1_000);
        payouts.request(UUID.randomUUID().toString(), new PayoutRequest(account, 300L, "USD"));
        awaitAllPayoutsFinal();
        PROVIDER.reset();

        assertThat(job.run()).isEqualTo(1);
        assertThat(job.run()).as("rerunning does not duplicate exceptions").isZero();
        assertThat(job.exceptions()).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo("COMPLETED_IN_LEDGER_ONLY"));
    }

    private void awaitAllPayoutsFinal() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc
                .sql("select count(*) from payouts where status not in ('COMPLETED', 'FAILED')")
                .query(Long.class).single() == 0);
    }
}
