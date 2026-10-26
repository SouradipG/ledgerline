package dev.souradip.ledgerline.payout;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record PayoutRequest(
        @NotNull Long accountId,
        @NotNull @Positive Long amountMinor,
        @NotNull @Pattern(regexp = "USD|EUR|GBP|INR") String currency) {

    public String fingerprint() {
        String canonical = "payout|" + accountId + "|" + amountMinor + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
