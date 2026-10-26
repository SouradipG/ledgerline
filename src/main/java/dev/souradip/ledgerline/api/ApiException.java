package dev.souradip.ledgerline.api;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an HTTP status and a stable error code clients can switch on. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
