package dev.souradip.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.TransferRequest;
import dev.souradip.ledgerline.transfer.TransferService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A client times out and retries, or a load balancer replays a request. The same key must never
 * move money twice, even when the copies arrive at the same time on different threads.
 */
class IdempotencyTest extends IntegrationTestBase {

    @Autowired
    TransferService transfers;

    @Test
    void sameKeyFromManyThreadsPostsOneTransfer() throws Exception {
        long from = ledger.openAccount(10_000);
        long to = ledger.openAccount(0);
        long before = ledger.transferCount();
        TransferRequest request = new TransferRequest(from, to, 500L, "USD");

        List<Concurrently.Outcome<StoredResponse>> outcomes =
                Concurrently.run(50, i -> transfers.transfer("retry-storm", request, LockingMode.PESSIMISTIC));

        assertThat(ledger.transferCount() - before).isEqualTo(1);
        assertThat(ledger.balanceOf(from)).isEqualTo(9_500);

        Set<String> bodies = outcomes.stream()
                .filter(o -> !o.failed())
                .map(o -> o.value().status() + " " + o.value().body())
                .collect(Collectors.toSet());
        assertThat(bodies).as("every completed response is identical").hasSize(1);
        assertThat(bodies.iterator().next()).startsWith("201 ");

        assertThat(outcomes).filteredOn(Concurrently.Outcome::failed)
                .allSatisfy(o -> assertThat(((ApiException) o.error()).code()).isEqualTo("REQUEST_IN_PROGRESS"));
        ledger.assertBooksBalance();
    }

    @Test
    void retryAfterCompletionReplaysTheOriginalResponse() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        TransferRequest request = new TransferRequest(from, to, 300L, "USD");

        StoredResponse first = transfers.transfer("pay-invoice-42", request, LockingMode.PESSIMISTIC);
        StoredResponse retry = transfers.transfer("pay-invoice-42", request, LockingMode.PESSIMISTIC);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.status()).isEqualTo(first.status());
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(ledger.balanceOf(from)).isEqualTo(700);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        transfers.transfer("order-7", new TransferRequest(from, to, 100L, "USD"), LockingMode.PESSIMISTIC);

        assertThatThrownBy(() -> transfers.transfer("order-7", new TransferRequest(from, to, 999L, "USD"), LockingMode.PESSIMISTIC))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(ledger.balanceOf(from)).isEqualTo(900);
    }

    @Test
    void aRejectedTransferIsReplayedAsTheSameRejection() {
        long from = ledger.openAccount(50);
        long to = ledger.openAccount(0);
        TransferRequest request = new TransferRequest(from, to, 80L, "USD");

        StoredResponse first = transfers.transfer("too-much", request, LockingMode.CONDITIONAL);
        StoredResponse retry = transfers.transfer("too-much", request, LockingMode.CONDITIONAL);

        assertThat(first.status()).isEqualTo(422);
        assertThat(first.body()).contains("INSUFFICIENT_FUNDS");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).isEqualTo(first.body());
    }

    @Test
    void aKeyAbandonedByACrashedInstanceCanBeTakenOver() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        TransferRequest request = new TransferRequest(from, to, 100L, "USD");

        // Simulates an instance that claimed the key and died before doing the work.
        jdbc.sql("""
                        insert into idempotency_keys (key, request_hash, status, locked_at)
                        values ('orphaned', :hash, 'IN_PROGRESS', now() - interval '1 hour')
                        """)
                .param("hash", request.fingerprint())
                .update();

        StoredResponse response = transfers.transfer("orphaned", request, LockingMode.PESSIMISTIC);

        assertThat(response.status()).isEqualTo(201);
        assertThat(ledger.balanceOf(from)).isEqualTo(900);
    }

    @Test
    void aFreshInProgressKeyIsNotTakenOver() {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        TransferRequest request = new TransferRequest(from, to, 100L, "USD");
        jdbc.sql("insert into idempotency_keys (key, request_hash, status) values ('busy', :hash, 'IN_PROGRESS')")
                .param("hash", request.fingerprint())
                .update();

        assertThatThrownBy(() -> transfers.transfer("busy", request, LockingMode.PESSIMISTIC))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REQUEST_IN_PROGRESS"));
        assertThat(ledger.balanceOf(from)).isEqualTo(1_000);
    }
}
