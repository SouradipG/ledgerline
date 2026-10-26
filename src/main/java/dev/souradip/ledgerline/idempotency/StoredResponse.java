package dev.souradip.ledgerline.idempotency;

/** The exact status and JSON body sent for a key, so a retry gets byte for byte the same answer. */
public record StoredResponse(int status, String body, boolean replayed) {

    public static StoredResponse fresh(int status, String body) {
        return new StoredResponse(status, body, false);
    }

    StoredResponse asReplay() {
        return new StoredResponse(status, body, true);
    }
}
