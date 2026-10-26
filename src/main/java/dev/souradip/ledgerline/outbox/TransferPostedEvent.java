package dev.souradip.ledgerline.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferPostedEvent(
        UUID eventId,
        UUID transferId,
        long fromAccountId,
        long toAccountId,
        long amountMinor,
        String currency,
        OffsetDateTime postedAt) {

    public static final String TYPE = "TransferPosted";
}
