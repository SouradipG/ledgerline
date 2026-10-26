package dev.souradip.ledgerline.provider;

public sealed interface ProviderResult {

    record Settled(String reference) implements ProviderResult {}

    record Declined(String reason) implements ProviderResult {}

    /** The provider has no record of this payment, so submitting it is safe. */
    record NotFound() implements ProviderResult {}

    /**
     * Timeout, connection error or 5xx. The payment may or may not have happened. The only safe
     * next step is to ask the provider, never to assume either outcome.
     */
    record Unknown(String reason) implements ProviderResult {}
}
