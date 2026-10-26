package dev.souradip.ledgerline.locking;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SELECT ... FOR UPDATE on both rows, check, then write. Simple to reason about and never
 * retries, at the cost of holding row locks for the whole check. Best when contention is high.
 */
@Component
class PessimisticDebitStrategy implements DebitStrategy {

    private final JdbcClient jdbc;

    PessimisticDebitStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.PESSIMISTIC;
    }

    @Override
    public void move(long fromAccountId, long toAccountId, long amountMinor) {
        // One statement per row, in id order, so the lock order is explicit and not left to the planner.
        Map<Long, BalanceRow> locked = new HashMap<>();
        for (long id : BalanceOrder.ascending(fromAccountId, toAccountId)) {
            locked.put(id, jdbc.sql("""
                            select account_id, balance_minor, allow_negative, version
                            from balances where account_id = :id for update
                            """)
                    .param("id", id)
                    .query(BalanceRow.class)
                    .single());
        }

        if (!locked.get(fromAccountId).canDebit(amountMinor)) {
            throw new InsufficientFundsException(fromAccountId);
        }
        for (long id : BalanceOrder.ascending(fromAccountId, toAccountId)) {
            long delta = id == fromAccountId ? -amountMinor : amountMinor;
            jdbc.sql("update balances set balance_minor = balance_minor + :delta, version = version + 1 where account_id = :id")
                    .param("delta", delta)
                    .param("id", id)
                    .update();
        }
    }
}
