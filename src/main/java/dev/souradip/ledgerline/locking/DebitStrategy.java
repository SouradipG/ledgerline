package dev.souradip.ledgerline.locking;

/**
 * Moves money between two balance rows inside the caller's transaction.
 *
 * <p>Three implementations exist so they can be benchmarked against each other under the same
 * load. All of them touch the two rows in ascending account id order. If one transfer locked A
 * then B while another locked B then A, Postgres would detect a deadlock and abort one of them.
 * A fixed global order makes that cycle impossible.
 */
public interface DebitStrategy {

    LockingMode mode();

    /**
     * @throws InsufficientFundsException if the source cannot cover the amount
     * @throws OptimisticConflictException if a concurrent write was detected (optimistic mode only)
     */
    void move(long fromAccountId, long toAccountId, long amountMinor);
}
