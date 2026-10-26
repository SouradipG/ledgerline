package dev.souradip.ledgerline.idempotency;

import dev.souradip.ledgerline.LedgerProperties;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys live in Postgres, next to the data they protect, not in an in memory map.
 * A map works on one instance and silently fails once a load balancer sends the retry to a
 * different instance.
 *
 * <p>The claim runs in its own short transaction and commits immediately, so concurrent requests
 * with the same key see it. Completion runs inside the transfer's transaction, so the stored
 * response and the money movement commit or roll back together.
 */
@Repository
public class IdempotencyStore {

    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final Duration lease;

    public IdempotencyStore(JdbcClient jdbc, LedgerProperties properties) {
        this.jdbc = jdbc;
        this.lease = properties.idempotency().lease();
    }

    public Claim claim(String key, String requestHash) {
        // The loop covers a narrow race: our insert conflicts, but the other request releases
        // its key before we read it. Trying again lets us claim it properly.
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            int inserted = jdbc.sql("""
                            insert into idempotency_keys (key, request_hash, status)
                            values (:key, :hash, 'IN_PROGRESS')
                            on conflict (key) do nothing
                            """)
                    .param("key", key)
                    .param("hash", requestHash)
                    .update();
            if (inserted == 1) {
                return new Claim.Acquired();
            }

            Optional<KeyRow> existing = jdbc.sql("""
                            select request_hash, status, response_code, response_body
                            from idempotency_keys where key = :key
                            """)
                    .param("key", key)
                    .query(KeyRow.class)
                    .optional();
            if (existing.isEmpty()) {
                continue;
            }

            KeyRow row = existing.get();
            if (!row.requestHash().equals(requestHash)) {
                return new Claim.Mismatch();
            }
            if ("COMPLETED".equals(row.status())) {
                return new Claim.Replay(new StoredResponse(row.responseCode(), row.responseBody(), true));
            }
            return takeOverIfAbandoned(key) ? new Claim.Acquired() : new Claim.InFlight();
        }
        return new Claim.InFlight();
    }

    /**
     * An IN_PROGRESS key whose lease ran out most likely belongs to an instance that crashed
     * mid request. Only one retry can win this conditional update. If the original owner was
     * merely slow rather than dead, the unique idempotency_key column on transfers still stops
     * the money moving twice.
     */
    private boolean takeOverIfAbandoned(String key) {
        return jdbc.sql("""
                        update idempotency_keys set locked_at = now()
                        where key = :key and status = 'IN_PROGRESS'
                          and locked_at < now() - make_interval(secs => :leaseSeconds)
                        """)
                .param("key", key)
                .param("leaseSeconds", lease.toMillis() / 1000.0)
                .update() == 1;
    }

    /** Must be called inside the transaction that did the work. */
    public void complete(String key, StoredResponse response) {
        jdbc.sql("""
                        update idempotency_keys
                        set status = 'COMPLETED', response_code = :code, response_body = :body
                        where key = :key
                        """)
                .param("code", response.status())
                .param("body", response.body())
                .param("key", key)
                .update();
    }

    /** Gives the key back after an unexpected failure so the client's retry can run the request. */
    public void release(String key) {
        jdbc.sql("delete from idempotency_keys where key = :key and status = 'IN_PROGRESS'")
                .param("key", key)
                .update();
    }

    record KeyRow(String requestHash, String status, Integer responseCode, String responseBody) {}
}
