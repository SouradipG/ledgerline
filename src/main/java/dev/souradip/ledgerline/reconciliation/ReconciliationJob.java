package dev.souradip.ledgerline.reconciliation;

import dev.souradip.ledgerline.provider.PaymentProviderClient;
import dev.souradip.ledgerline.provider.ProviderPayment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Compares what the provider says it settled with what the ledger says it completed.
 *
 * <p>Retries, timeouts and idempotency keys make double payment unlikely, but "unlikely" is not
 * what finance teams sign off on. Every real payments system has a reconciliation step that
 * assumes something slipped through and goes looking for it. Mismatches are written to
 * recon_exceptions for a human; the job never edits money itself.
 *
 * <p>Payouts still RESERVED or UNKNOWN are skipped, since the worker has not finished with them.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final PaymentProviderClient provider;
    private final JdbcClient jdbc;

    ReconciliationJob(PaymentProviderClient provider, JdbcClient jdbc) {
        this.provider = provider;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${ledger.reconciliation.cron:0 0 2 * * *}")
    void nightly() {
        int found = run();
        log.info("reconciliation finished, {} new exceptions", found);
    }

    public int run() {
        Map<String, ProviderPayment> settled = provider.settlements().stream()
                .collect(Collectors.toMap(ProviderPayment::paymentId, Function.identity()));

        Map<String, LedgerPayout> ledger = new HashMap<>();
        jdbc.sql("select id::text as id, amount_minor, status from payouts")
                .query(LedgerPayout.class)
                .list()
                .forEach(p -> ledger.put(p.id(), p));

        int found = 0;
        for (ProviderPayment payment : settled.values()) {
            LedgerPayout ours = ledger.get(payment.paymentId());
            if (ours == null) {
                found += record(payment.paymentId(), "SETTLED_AT_PROVIDER_ONLY", "provider settled a payment the ledger has never seen");
            } else if (ours.status().equals("RESERVED") || ours.status().equals("UNKNOWN")) {
                continue;
            } else if (!ours.status().equals("COMPLETED")) {
                found += record(payment.paymentId(), "SETTLED_BUT_NOT_COMPLETED", "provider settled it, ledger has it as " + ours.status());
            } else if (ours.amountMinor() != payment.amountMinor()) {
                found += record(payment.paymentId(), "AMOUNT_MISMATCH",
                        "ledger " + ours.amountMinor() + ", provider " + payment.amountMinor());
            }
        }
        for (LedgerPayout ours : ledger.values()) {
            if (ours.status().equals("COMPLETED") && !settled.containsKey(ours.id())) {
                found += record(ours.id(), "COMPLETED_IN_LEDGER_ONLY", "ledger completed it, provider has no settlement");
            }
        }
        return found;
    }

    public List<ReconException> exceptions() {
        return jdbc.sql("select payment_id, kind, details from recon_exceptions order by id").query(ReconException.class).list();
    }

    private int record(String paymentId, String kind, String details) {
        return jdbc.sql("""
                        insert into recon_exceptions (payment_id, kind, details) values (:id, :kind, :details)
                        on conflict (payment_id, kind) do nothing
                        """)
                .param("id", paymentId)
                .param("kind", kind)
                .param("details", details)
                .update();
    }

    record LedgerPayout(String id, long amountMinor, String status) {}

    public record ReconException(String paymentId, String kind, String details) {}
}
