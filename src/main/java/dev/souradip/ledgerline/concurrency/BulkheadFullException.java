package dev.souradip.ledgerline.concurrency;

public class BulkheadFullException extends RuntimeException {

    public BulkheadFullException(String message) {
        super(message);
    }
}
