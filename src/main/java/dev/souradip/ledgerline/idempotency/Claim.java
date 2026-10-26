package dev.souradip.ledgerline.idempotency;

/** What happened when a request tried to claim its idempotency key. */
public sealed interface Claim {

    /** This request owns the key and must do the work. */
    record Acquired() implements Claim {}

    /** The key already finished. Return the stored response unchanged. */
    record Replay(StoredResponse response) implements Claim {}

    /** Another request with this key is still running. */
    record InFlight() implements Claim {}

    /** The key was used before with a different request body. */
    record Mismatch() implements Claim {}
}
