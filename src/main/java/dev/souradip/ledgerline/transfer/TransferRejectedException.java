package dev.souradip.ledgerline.transfer;

import dev.souradip.ledgerline.api.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A business rule said no. The outcome is final for this request, so it is stored against the
 * idempotency key and replayed, the same way a success is.
 */
public class TransferRejectedException extends ApiException {

    public TransferRejectedException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
