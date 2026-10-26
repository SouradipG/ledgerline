package dev.souradip.ledgerline.locking;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read without locks, then write only if the version is unchanged. No locks are held while the
 * check runs, which is cheap when conflicts are rare. Under heavy contention on one account most
 * attempts lose and retry, so throughput collapses. The benchmark shows where that line is.
 */
@Component
class OptimisticDebitStrategy implements DebitStrategy {

    private final JdbcClient jdbc;

    OptimisticDebitStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.OPTIMISTIC;
    }

    @Override
    public void move(long fromAccountId, long toAccountId, long amountMinor) {
        Map<Long, BalanceRow> snapshot = new HashMap<>();
        jdbc.sql("""
                        select account_id, balance_minor, allow_negative, version
                        from balances where account_id in (:a, :b)
                        """)
                .param("a", fromAccountId)
                .param("b", toAccountId)
                .query(BalanceRow.class)
                .list()
                .forEach(row -> snapshot.put(row.accountId(), row));

        if (!snapshot.get(fromAccountId).canDebit(amountMinor)) {
            throw new InsufficientFundsException(fromAccountId);
        }
        // The UPDATEs still take row locks until commit, so the id ordering matters here too.
        for (long id : BalanceOrder.ascending(fromAccountId, toAccountId)) {
            long delta = id == fromAccountId ? -amountMinor : amountMinor;
            int updated = jdbc.sql("""
                            update balances set balance_minor = balance_minor + :delta, version = version + 1
                            where account_id = :id and version = :version
                            """)
                    .param("delta", delta)
                    .param("id", id)
                    .param("version", snapshot.get(id).version())
                    .update();
            if (updated == 0) {
                throw new OptimisticConflictException(id);
            }
        }
    }
}
