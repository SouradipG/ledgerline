package dev.souradip.ledgerline.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.souradip.ledgerline.Concurrently;
import dev.souradip.ledgerline.IntegrationTestBase;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.simulator.PaymentProviderSimulator.Behavior;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class PayoutSagaTest extends IntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    PayoutService payouts;

    @Autowired
    PayoutRepository repository;

    @Autowired
    PayoutWorker worker;

    @Autowired
    JsonMapper json;

    @Test
    void settledPayoutLeavesTheLedger() {
        long account = ledger.openAccount(1_000);

        UUID id = request(account, 300);

        assertThat(ledger.balanceOf(account)).isEqualTo(700);
        await().atMost(WAIT).until(() -> status(id).equals("COMPLETED"));
        assertThat(ledger.clearingBalance("USD")).isZero();
        assertThat(PROVIDER.executionsOf(id.toString())).isEqualTo(1);
        ledger.assertBooksBalance();
    }

    @Test
    void declinedPayoutIsRefunded() {
        PROVIDER.behave(Behavior.DECLINE);
        long account = ledger.openAccount(1_000);

        UUID id = request(account, 300);

        await().atMost(WAIT).until(() -> status(id).equals("FAILED"));
        assertThat(ledger.balanceOf(account)).isEqualTo(1_000);
        assertThat(ledger.clearingBalance("USD")).isZero();
        assertThat(PROVIDER.executionsOf(id.toString())).isZero();
        ledger.assertBooksBalance();
    }

    /**
     * The dangerous one. The provider pays, then the response never arrives. Treating the
     * timeout as a failure would refund money that has already left. The worker must record
     * UNKNOWN, ask the provider, and only then complete.
     */
    @Test
    void timeoutAfterTheProviderPaidIsResolvedByLookupNotByRefund() {
        PROVIDER.hangFor(Duration.ofSeconds(2));
        PROVIDER.behave(Behavior.SETTLE_THEN_HANG);
        long account = ledger.openAccount(1_000);

        UUID id = request(account, 300);

        await().atMost(WAIT).until(() -> repository.find(id).orElseThrow().attempts() >= 1);
        PROVIDER.behave(Behavior.SETTLE);
        await().atMost(WAIT).until(() -> status(id).equals("COMPLETED"));

        assertThat(PROVIDER.executionsOf(id.toString())).isEqualTo(1);
        assertThat(ledger.balanceOf(account)).as("never refunded").isEqualTo(700);
        ledger.assertBooksBalance();
    }

    @Test
    void providerOutageIsRetriedWithBackoffUntilItRecovers() {
        PROVIDER.behave(Behavior.UNAVAILABLE);
        long account = ledger.openAccount(1_000);

        UUID id = request(account, 250);

        await().atMost(WAIT).until(() -> repository.find(id).orElseThrow().attempts() >= 2);
        assertThat(status(id)).isEqualTo("UNKNOWN");
        assertThat(ledger.clearingBalance("USD")).as("money stays visible in clearing").isEqualTo(250);

        PROVIDER.behave(Behavior.SETTLE);
        await().atMost(WAIT).until(() -> status(id).equals("COMPLETED"));
        assertThat(PROVIDER.executionsOf(id.toString())).isEqualTo(1);
        ledger.assertBooksBalance();
    }

    @Test
    void aPayoutThatNeverResolvesIsParkedForReviewWithFundsHeld() {
        PROVIDER.behave(Behavior.UNAVAILABLE);
        long account = ledger.openAccount(1_000);

        UUID id = request(account, 400);

        await().atMost(WAIT).until(() -> status(id).equals("NEEDS_REVIEW"));
        assertThat(ledger.balanceOf(account)).isEqualTo(600);
        assertThat(ledger.clearingBalance("USD")).isEqualTo(400);
        ledger.assertBooksBalance();
    }

    /** Many workers poll at once, as they would with several instances. Each payout is paid once. */
    @Test
    void concurrentWorkersNeverPayTwice() throws Exception {
        long account = ledger.openAccount(100_000);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(request(account, 100));
        }

        Concurrently.run(8, i -> {
            worker.poll();
            return null;
        });

        await().atMost(WAIT).until(() -> ids.stream().allMatch(id -> status(id).equals("COMPLETED")));
        assertThat(ids).allSatisfy(id -> assertThat(PROVIDER.executionsOf(id.toString())).isEqualTo(1));
        assertThat(ledger.balanceOf(account)).isEqualTo(96_000);
        ledger.assertBooksBalance();
    }

    @Test
    void retryingThePayoutRequestCreatesOnePayout() {
        long account = ledger.openAccount(1_000);
        PayoutRequest request = new PayoutRequest(account, 100L, "USD");

        StoredResponse first = payouts.request("withdraw-1", request);
        StoredResponse again = payouts.request("withdraw-1", request);

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(jdbc.sql("select count(*) from payouts").query(Long.class).single()).isEqualTo(1);
        assertThat(ledger.balanceOf(account)).isEqualTo(900);
    }

    @Test
    void payoutLargerThanTheBalanceIsRejectedUpFront() {
        long account = ledger.openAccount(100);

        StoredResponse response = payouts.request(UUID.randomUUID().toString(), new PayoutRequest(account, 500L, "USD"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("INSUFFICIENT_FUNDS");
        assertThat(PROVIDER.requestCount()).isZero();
    }

    private UUID request(long account, long amount) {
        StoredResponse response = payouts.request(UUID.randomUUID().toString(), new PayoutRequest(account, amount, "USD"));
        assertThat(response.status()).isEqualTo(202);
        return json.readValue(response.body(), PayoutView.class).id();
    }

    private String status(UUID id) {
        return repository.find(id).orElseThrow().status();
    }
}
