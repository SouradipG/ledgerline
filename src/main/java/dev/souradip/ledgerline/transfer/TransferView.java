package dev.souradip.ledgerline.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferView(
        UUID id,
        long fromAccountId,
        long toAccountId,
        long amountMinor,
        String currency,
        String status,
        OffsetDateTime createdAt) {}
