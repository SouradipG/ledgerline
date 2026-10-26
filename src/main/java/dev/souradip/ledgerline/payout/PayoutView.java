package dev.souradip.ledgerline.payout;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PayoutView(
        UUID id,
        long accountId,
        long amountMinor,
        String currency,
        String status,
        int attempts,
        String providerReference,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
