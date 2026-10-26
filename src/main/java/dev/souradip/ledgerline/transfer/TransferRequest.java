package dev.souradip.ledgerline.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Amounts are integer minor units (cents, paise). Never floating point. */
public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @Positive Long amountMinor,
        @NotNull @Pattern(regexp = "USD|EUR|GBP|INR") String currency) {

    /** Fingerprint used to reject reuse of an idempotency key with a different request. */
    public String fingerprint() {
        String canonical = fromAccountId + "|" + toAccountId + "|" + amountMinor + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
