package dev.souradip.ledgerline.locking;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId) {
        super("account " + accountId + " has insufficient funds");
    }
}
