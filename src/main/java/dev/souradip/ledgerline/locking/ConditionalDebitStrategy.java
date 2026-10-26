package dev.souradip.ledgerline.locking;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Push the rule into the UPDATE itself: debit only where the balance covers it. One round trip
 * per row and no retries. Postgres re-checks the WHERE clause against the latest committed row
 * after waiting for a concurrent writer, so two debits can never both pass on stale data.
 */
@Component
class ConditionalDebitStrategy implements DebitStrategy {

    private final JdbcClient jdbc;

    ConditionalDebitStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.CONDITIONAL;
    }

    @Override
    public void move(long fromAccountId, long toAccountId, long amountMinor) {
        for (long id : BalanceOrder.ascending(fromAccountId, toAccountId)) {
            if (id == fromAccountId) {
                int updated = jdbc.sql("""
                                update balances set balance_minor = balance_minor - :amount, version = version + 1
                                where account_id = :id and (allow_negative or balance_minor >= :amount)
                                """)
                        .param("amount", amountMinor)
                        .param("id", id)
                        .update();
                if (updated == 0) {
                    throw new InsufficientFundsException(id);
                }
            } else {
                jdbc.sql("update balances set balance_minor = balance_minor + :amount, version = version + 1 where account_id = :id")
                        .param("amount", amountMinor)
                        .param("id", id)
                        .update();
            }
        }
    }
}
