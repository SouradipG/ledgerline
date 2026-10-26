package dev.souradip.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.TransferRequest;
import dev.souradip.ledgerline.transfer.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Random transfers between 20 accounts from 32 threads at once, many of them overdrawing on
 * purpose. Whatever interleaving happens, the books must still balance at the end.
 */
class LedgerInvariantTest extends PostgresTestBase {

    private static final int ACCOUNTS = 20;
    private static final int THREADS = 32;
    private static final int TRANSFERS_PER_THREAD = 60;

    @Autowired
    TransferService transfers;

    @ParameterizedTest
    @EnumSource(LockingMode.class)
    void booksBalanceAfterRandomConcurrentTransfers(LockingMode mode) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            ids.add(ledger.openAccount(10_000));
        }

        var outcomes = Concurrently.run(THREADS, thread -> {
            var random = ThreadLocalRandom.current();
            int unexpected = 0;
            for (int n = 0; n < TRANSFERS_PER_THREAD; n++) {
                long from = ids.get(random.nextInt(ACCOUNTS));
                long to = ids.get(random.nextInt(ACCOUNTS));
                if (from == to) {
                    continue;
                }
                try {
                    transfers.transfer(UUID.randomUUID().toString(),
                            new TransferRequest(from, to, random.nextLong(1, 6_000), "USD"), mode);
                } catch (ApiException e) {
                    if (!e.code().equals("CONCURRENT_UPDATE")) {
                        unexpected++;
                    }
                }
            }
            return unexpected;
        });

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.value()).isZero();
        });
        long customerTotal = ids.stream().mapToLong(ledger::balanceOf).sum();
        assertThat(customerTotal).isEqualTo(ACCOUNTS * 10_000L);
        ledger.assertBooksBalance();
    }
}
