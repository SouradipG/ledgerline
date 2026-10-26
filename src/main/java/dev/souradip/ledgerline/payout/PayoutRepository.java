package dev.souradip.ledgerline.payout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PayoutRepository {

    private static final String COLUMNS = """
            id, account_id, amount_minor, currency, status, attempts, provider_reference, last_error, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    PayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertReserved(UUID id, String idempotencyKey, PayoutRequest request) {
        jdbc.sql("""
                        insert into payouts (id, idempotency_key, account_id, amount_minor, currency, status)
                        values (:id, :key, :account, :amount, :currency, 'RESERVED')
                        """)
                .param("id", id)
                .param("key", idempotencyKey)
                .param("account", request.accountId())
                .param("amount", request.amountMinor())
                .param("currency", request.currency())
                .update();
    }

    public Optional<PayoutView> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from payouts where id = :id")
                .param("id", id)
                .query(PayoutView.class)
                .optional();
    }

    /**
     * Picks due payouts and pushes their next attempt out by the lease, in one short transaction.
     * SKIP LOCKED lets several workers poll at once without picking the same payout. The lease
     * means a worker that dies mid call does not strand the payout: it becomes due again later.
     */
    List<PayoutView> claimDue(int limit, Duration lease) {
        return jdbc.sql("""
                        update payouts set next_attempt_at = now() + make_interval(secs => :lease)
                        where id in (
                            select id from payouts
                            where status in ('RESERVED', 'UNKNOWN') and next_attempt_at <= now()
                            order by next_attempt_at
                            limit :limit
                            for update skip locked)
                        returning
                        """ + COLUMNS)
                .param("lease", lease.toMillis() / 1000.0)
                .param("limit", limit)
                .query(PayoutView.class)
                .list();
    }

    /** Moves an open payout to a final state. Returns false if another worker already did. */
    boolean finish(UUID id, String status, String providerReference, String error) {
        return jdbc.sql("""
                        update payouts set status = :status, provider_reference = :reference, last_error = :error,
                          attempts = attempts + 1, updated_at = now()
                        where id = :id and status in ('RESERVED', 'UNKNOWN')
                        """)
                .param("status", status)
                .param("reference", providerReference)
                .param("error", error)
                .param("id", id)
                .update() == 1;
    }

    void markUnknown(UUID id, String error, Duration retryIn, int maxAttempts) {
        jdbc.sql("""
                        update payouts set
                          attempts = attempts + 1,
                          status = case when attempts + 1 >= :maxAttempts then 'NEEDS_REVIEW' else 'UNKNOWN' end,
                          last_error = :error,
                          next_attempt_at = now() + make_interval(secs => :retryIn),
                          updated_at = now()
                        where id = :id and status in ('RESERVED', 'UNKNOWN')
                        """)
                .param("maxAttempts", maxAttempts)
                .param("error", error)
                .param("retryIn", retryIn.toMillis() / 1000.0)
                .param("id", id)
                .update();
    }
}
