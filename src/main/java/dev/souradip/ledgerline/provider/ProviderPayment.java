package dev.souradip.ledgerline.provider;

/** The provider's view of a payment, as returned by its API. */
public record ProviderPayment(String paymentId, String status, String reference, long amountMinor, String currency, String reason) {}
