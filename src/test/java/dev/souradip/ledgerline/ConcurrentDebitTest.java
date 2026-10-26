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
 * The classic double spend. An account holds 100 and 200 requests try to take 80 at the same
 * moment. A naive read, check, write lets several of them through. Exactly one may succeed.
 */
class ConcurrentDebitTest extends IntegrationTestBase {

    @Autowired
    TransferService transfers;

    @ParameterizedTest
    @EnumSource(LockingMode.class)
    void onlyOneOfManyConcurrentDebitsSucceeds(LockingMode mode) throws Exception {
        long source = ledger.openAccount(100);
        long target = ledger.openAccount(0);

        List<Concurrently.Outcome<StoredResponse>> outcomes = Concurrently.run(200, i ->
                transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(source, target, 80L, "USD"), mode));

        long succeeded = outcomes.stream().filter(o -> !o.failed() && o.value().status() == 201).count();
        long refused = outcomes.stream().filter(o -> !o.failed() && o.value().status() == 422).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error() instanceof ApiException e && e.code().equals("CONCURRENT_UPDATE"))
                .count();

        assertThat(succeeded).isEqualTo(1);
        assertThat(succeeded + refused + conflicts)
                .as("every request ends as success, insufficient funds or a retryable conflict")
                .isEqualTo(200);
        assertThat(ledger.balanceOf(source)).isEqualTo(20);
        assertThat(ledger.balanceOf(target)).isEqualTo(80);
        ledger.assertBooksBalance();
    }
}
