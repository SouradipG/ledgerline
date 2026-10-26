package dev.souradip.ledgerline.payout;

import dev.souradip.ledgerline.account.Account;
import dev.souradip.ledgerline.account.AccountRepository;
import dev.souradip.ledgerline.api.BusinessRuleException;
import dev.souradip.ledgerline.concurrency.DbBulkhead;
import dev.souradip.ledgerline.idempotency.IdempotencyStore;
import dev.souradip.ledgerline.idempotency.IdempotentRequests;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.Ledger;
import dev.souradip.ledgerline.transfer.TransferRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts a payout and reserves the money. The provider is not called on the request thread.
 *
 * <p>The API answers 202 as soon as the funds sit in the clearing account. Calling the provider
 * inline would tie our latency and availability to theirs, and a timeout there would leave the
 * client guessing. {@link PayoutWorker} does the provider call in the background.
 */
@Service
public class PayoutService {

    private final IdempotentRequests idempotent;
    private final IdempotencyStore idempotency;
    private final DbBulkhead bulkhead;
    private final TransactionTemplate tx;
    private final Ledger ledger;
    private final AccountRepository accounts;
    private final PayoutRepository payouts;
    private final JsonMapper json;

    PayoutService(IdempotentRequests idempotent, IdempotencyStore idempotency, DbBulkhead bulkhead, TransactionTemplate tx,
                  Ledger ledger, AccountRepository accounts, PayoutRepository payouts, JsonMapper json) {
        this.idempotent = idempotent;
        this.idempotency = idempotency;
        this.bulkhead = bulkhead;
        this.tx = tx;
        this.ledger = ledger;
        this.accounts = accounts;
        this.payouts = payouts;
        this.json = json;
    }

    public StoredResponse request(String idempotencyKey, PayoutRequest request) {
        return bulkhead.call(() -> idempotent.execute(idempotencyKey, request.fingerprint(), () -> tx.execute(status -> {
            Account account = accounts.findAccount(request.accountId())
                    .filter(a -> a.kind().equals("CUSTOMER"))
                    .orElseThrow(() -> new BusinessRuleException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                            "customer account " + request.accountId() + " does not exist"));

            UUID id = UUID.randomUUID();
            long clearing = accounts.systemAccount("PAYOUT_CLEARING", account.currency());
            ledger.post("payout-reserve:" + id,
                    new TransferRequest(request.accountId(), clearing, request.amountMinor(), request.currency()),
                    LockingMode.CONDITIONAL);
            payouts.insertReserved(id, idempotencyKey, request);

            StoredResponse response = StoredResponse.fresh(HttpStatus.ACCEPTED.value(),
                    json.writeValueAsString(payouts.find(id).orElseThrow()));
            idempotency.complete(idempotencyKey, response);
            return response;
        })));
    }
}
