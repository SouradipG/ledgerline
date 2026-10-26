package dev.souradip.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.TransferRequest;
import dev.souradip.ledgerline.transfer.TransferService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A pays B while B pays A. If each transfer locked its own source first, two of them could each
 * hold one row and wait for the other, and Postgres would abort one with a deadlock error.
 * Locking in ascending id order removes the cycle.
 */
class OpposingTransfersTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @ParameterizedTest
    @EnumSource(LockingMode.class)
    void opposingTransfersNeverDeadlock(LockingMode mode) throws Exception {
        long a = ledger.openAccount(1_000_000);
        long b = ledger.openAccount(1_000_000);

        List<Concurrently.Outcome<StoredResponse>> outcomes = Concurrently.run(1_000, i -> {
            TransferRequest request = i % 2 == 0
                    ? new TransferRequest(a, b, 1L, "USD")
                    : new TransferRequest(b, a, 1L, "USD");
            return transfers.transfer(UUID.randomUUID().toString(), request, mode);
        });

        List<Throwable> unexpected = outcomes.stream()
                .map(Concurrently.Outcome::error)
                .filter(e -> e != null && !(e instanceof ApiException api && api.code().equals("CONCURRENT_UPDATE")))
                .toList();
        assertThat(unexpected).as("deadlocks or other unexpected errors").isEmpty();

        long posted = outcomes.stream().filter(o -> !o.failed() && o.value().status() == 201).count();
        if (mode != LockingMode.OPTIMISTIC) {
            assertThat(posted).isEqualTo(1_000);
        }
        assertThat(ledger.balanceOf(a) + ledger.balanceOf(b)).isEqualTo(2_000_000);
        ledger.assertBooksBalance();
    }
}
