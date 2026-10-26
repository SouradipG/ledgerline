package dev.souradip.ledgerline.transfer;

import dev.souradip.ledgerline.LedgerProperties;
import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.api.ErrorBody;
import dev.souradip.ledgerline.concurrency.DbBulkhead;
import dev.souradip.ledgerline.idempotency.Claim;
import dev.souradip.ledgerline.idempotency.IdempotencyStore;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.locking.OptimisticConflictException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a transfer exactly once per idempotency key.
 *
 * <pre>
 *   claim key (own tx, commits)  ->  move money + store response (one tx)  ->  reply
 *                                        |
 *                                        +-- unexpected failure: release key, client may retry
 * </pre>
 */
@Service
public class TransferService {

    private final Ledger ledger;
    private final IdempotencyStore idempotency;
    private final DbBulkhead bulkhead;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final LedgerProperties properties;

    TransferService(Ledger ledger, IdempotencyStore idempotency, DbBulkhead bulkhead,
                    TransactionTemplate tx, JsonMapper json, LedgerProperties properties) {
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.bulkhead = bulkhead;
        this.tx = tx;
        this.json = json;
        this.properties = properties;
    }

    public StoredResponse transfer(String idempotencyKey, TransferRequest request) {
        return transfer(idempotencyKey, request, properties.locking());
    }

    public StoredResponse transfer(String idempotencyKey, TransferRequest request, LockingMode mode) {
        return bulkhead.call(() -> run(idempotencyKey, request, mode));
    }

    private StoredResponse run(String key, TransferRequest request, LockingMode mode) {
        switch (idempotency.claim(key, request.fingerprint())) {
            case Claim.Replay replay -> {
                return replay.response();
            }
            case Claim.InFlight inFlight -> throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "a request with this idempotency key is still being processed");
            case Claim.Mismatch mismatch -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "this idempotency key was already used with a different request");
            case Claim.Acquired acquired -> {
                // fall through to do the work
            }
        }

        try {
            return executeWithRetry(key, request, mode);
        } catch (TransferRejectedException rejected) {
            StoredResponse response = StoredResponse.fresh(rejected.status().value(),
                    json.writeValueAsString(new ErrorBody(rejected.code(), rejected.getMessage())));
            tx.executeWithoutResult(status -> idempotency.complete(key, response));
            return response;
        } catch (RuntimeException unexpected) {
            idempotency.release(key);
            throw unexpected;
        }
    }

    private StoredResponse executeWithRetry(String key, TransferRequest request, LockingMode mode) {
        int maxAttempts = mode == LockingMode.OPTIMISTIC ? properties.optimistic().maxAttempts() : 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    TransferView view = ledger.post(key, request, mode);
                    StoredResponse response = StoredResponse.fresh(HttpStatus.CREATED.value(), json.writeValueAsString(view));
                    idempotency.complete(key, response);
                    return response;
                });
            } catch (OptimisticConflictException conflict) {
                if (attempt >= maxAttempts) {
                    throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                            "the account was updated concurrently, retry with the same idempotency key");
                }
                backoff(attempt);
            } catch (DuplicateKeyException alreadyPosted) {
                // Only reachable if the key lease expired while the first request was still alive.
                // The transfer row already exists, so answer with it instead of moving money again.
                return tx.execute(status -> {
                    StoredResponse response = StoredResponse.fresh(HttpStatus.CREATED.value(),
                            json.writeValueAsString(ledger.findByIdempotencyKey(key)));
                    idempotency.complete(key, response);
                    return response;
                });
            }
        }
    }

    /** Exponential backoff with full jitter, so retries from many threads do not line up again. */
    private static void backoff(int attempt) {
        long capMillis = Math.min(50, 2L << attempt);
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(capMillis + 1) * 1_000_000);
    }
}
