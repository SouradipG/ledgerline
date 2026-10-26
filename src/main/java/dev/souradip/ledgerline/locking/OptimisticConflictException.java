package dev.souradip.ledgerline.locking;

/** Thrown when a balance row changed between our read and our write. Safe to retry the whole transaction. */
public class OptimisticConflictException extends RuntimeException {

    public OptimisticConflictException(long accountId) {
        super("balance of account " + accountId + " changed concurrently");
    }
}
