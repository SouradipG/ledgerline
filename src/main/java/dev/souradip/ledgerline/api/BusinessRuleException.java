package dev.souradip.ledgerline.api;

import org.springframework.http.HttpStatus;

/**
 * A business rule said no, for example insufficient funds. The outcome is final for this
 * request, so it is stored against the idempotency key and replayed, the same way a success is.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
