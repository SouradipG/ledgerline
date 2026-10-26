package dev.souradip.ledgerline.idempotency;

import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.api.BusinessRuleException;
import dev.souradip.ledgerline.api.ErrorBody;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a write at most once per idempotency key.
 *
 * <pre>
 *   claim key (own tx, commits)  ->  work + store response (one tx, inside {@code work})  ->  reply
 *                                        |
 *                                        +-- business rule rejection: store it, replay it later
 *                                        +-- unexpected failure: release key, client may retry
 * </pre>
 */
@Component
public class IdempotentRequests {

    private final IdempotencyStore store;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    IdempotentRequests(IdempotencyStore store, TransactionTemplate tx, JsonMapper json) {
        this.store = store;
        this.tx = tx;
        this.json = json;
    }

    /**
     * @param work does the change and calls {@link IdempotencyStore#complete} in the same transaction
     */
    public StoredResponse execute(String key, String fingerprint, Supplier<StoredResponse> work) {
        switch (store.claim(key, fingerprint)) {
            case Claim.Replay replay -> {
                return replay.response();
            }
            case Claim.InFlight inFlight -> throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "a request with this idempotency key is still being processed");
            case Claim.Mismatch mismatch -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "this idempotency key was already used with a different request");
            case Claim.Acquired acquired -> {
                // this request owns the key
            }
        }

        try {
            return work.get();
        } catch (BusinessRuleException rejected) {
            StoredResponse response = StoredResponse.fresh(rejected.status().value(),
                    json.writeValueAsString(new ErrorBody(rejected.code(), rejected.getMessage())));
            tx.executeWithoutResult(status -> store.complete(key, response));
            return response;
        } catch (RuntimeException unexpected) {
            store.release(key);
            throw unexpected;
        }
    }
}
