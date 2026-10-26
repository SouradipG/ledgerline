package dev.souradip.ledgerline.payout;

import dev.souradip.ledgerline.LedgerProperties;
import dev.souradip.ledgerline.account.AccountRepository;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.provider.PaymentProviderClient;
import dev.souradip.ledgerline.provider.ProviderResult;
import dev.souradip.ledgerline.transfer.Ledger;
import dev.souradip.ledgerline.transfer.TransferRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives each payout to a final state. This is a small saga:
 *
 * <pre>
 *   reserve (customer -> clearing)        done by PayoutService
 *   call provider
 *     settled   -> clearing -> treasury   COMPLETED
 *     declined  -> clearing -> customer   FAILED     (compensation)
 *     no answer -> look up status before doing anything else   UNKNOWN
 * </pre>
 *
 * <p>The rule that matters: a timeout is not a failure. The provider may have paid the money and
 * the response got lost. Refunding the customer then would pay them twice. So an UNKNOWN payout
 * is only ever resolved by asking the provider what happened.
 *
 * <p>No database locks are held during the HTTP call. Payouts are claimed with a lease in one
 * short transaction, and each outcome is applied in another, guarded by a conditional status
 * update so two workers can never both settle or both refund the same payout.
 */
@Component
@ConditionalOnProperty(name = "ledger.payouts.worker-enabled", havingValue = "true", matchIfMissing = true)
public class PayoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);
    private static final int BATCH = 20;

    private final PayoutRepository payouts;
    private final PaymentProviderClient provider;
    private final Ledger ledger;
    private final AccountRepository accounts;
    private final TransactionTemplate tx;
    private final LedgerProperties.Payouts config;

    PayoutWorker(PayoutRepository payouts, PaymentProviderClient provider, Ledger ledger, AccountRepository accounts,
                 TransactionTemplate tx, LedgerProperties properties) {
        this.payouts = payouts;
        this.provider = provider;
        this.ledger = ledger;
        this.accounts = accounts;
        this.tx = tx;
        this.config = properties.payouts();
    }

    @Scheduled(fixedDelayString = "${ledger.payouts.poll-interval:500ms}")
    void poll() {
        try {
            List<PayoutView> due;
            do {
                due = payouts.claimDue(BATCH, config.lease());
                due.forEach(this::advance);
            } while (due.size() == BATCH);
        } catch (RuntimeException e) {
            log.warn("payout poll failed, will retry: {}", e.toString());
        }
    }

    void advance(PayoutView payout) {
        String paymentId = payout.id().toString();
        ProviderResult result = payout.status().equals("UNKNOWN") ? provider.lookup(paymentId) : new ProviderResult.NotFound();
        if (result instanceof ProviderResult.NotFound) {
            result = provider.submit(paymentId, payout.amountMinor(), payout.currency());
        }

        switch (result) {
            case ProviderResult.Settled settled -> settle(payout, settled.reference());
            case ProviderResult.Declined declined -> refund(payout, declined.reason());
            case ProviderResult.Unknown unknown -> {
                Duration retryIn = backoff(payout.attempts());
                log.info("payout {} outcome unknown ({}), checking again in {}", paymentId, unknown.reason(), retryIn);
                tx.executeWithoutResult(s -> payouts.markUnknown(payout.id(), unknown.reason(), retryIn, config.maxAttempts()));
            }
            case ProviderResult.NotFound notFound -> throw new IllegalStateException("submit never returns NotFound");
        }
    }

    private void settle(PayoutView payout, String reference) {
        tx.executeWithoutResult(s -> {
            if (payouts.finish(payout.id(), "COMPLETED", reference, null)) {
                long clearing = accounts.systemAccount("PAYOUT_CLEARING", payout.currency());
                long treasury = accounts.treasuryFor(payout.currency());
                ledger.post("payout-settle:" + payout.id(),
                        new TransferRequest(clearing, treasury, payout.amountMinor(), payout.currency()), LockingMode.CONDITIONAL);
            }
        });
    }

    private void refund(PayoutView payout, String reason) {
        tx.executeWithoutResult(s -> {
            if (payouts.finish(payout.id(), "FAILED", null, reason)) {
                long clearing = accounts.systemAccount("PAYOUT_CLEARING", payout.currency());
                ledger.post("payout-refund:" + payout.id(),
                        new TransferRequest(clearing, payout.accountId(), payout.amountMinor(), payout.currency()), LockingMode.CONDITIONAL);
            }
        });
    }

    /** Exponential backoff with full jitter, capped. Spreads retries so a recovering provider is not hit all at once. */
    private Duration backoff(int attempts) {
        long base = config.retryBase().toMillis();
        long cap = Math.min(config.retryMax().toMillis(), base << Math.min(attempts, 20));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(Math.max(1, cap)));
    }
}
