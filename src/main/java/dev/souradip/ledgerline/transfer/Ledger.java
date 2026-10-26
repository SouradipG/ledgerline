package dev.souradip.ledgerline.transfer;

import dev.souradip.ledgerline.account.Account;
import dev.souradip.ledgerline.account.AccountRepository;
import dev.souradip.ledgerline.locking.DebitStrategies;
import dev.souradip.ledgerline.locking.InsufficientFundsException;
import dev.souradip.ledgerline.locking.LockingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Double entry bookkeeping. Runs inside a transaction owned by the caller. */
@Component
class Ledger {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final DebitStrategies strategies;

    Ledger(JdbcClient jdbc, AccountRepository accounts, DebitStrategies strategies) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.strategies = strategies;
    }

    TransferView post(String idempotencyKey, TransferRequest request, LockingMode mode) {
        validate(request);
        try {
            strategies.get(mode).move(request.fromAccountId(), request.toAccountId(), request.amountMinor());
        } catch (InsufficientFundsException e) {
            throw new TransferRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", e.getMessage());
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = jdbc.sql("""
                        insert into transfers (id, idempotency_key, from_account_id, to_account_id, amount_minor, currency, status)
                        values (:id, :key, :from, :to, :amount, :currency, 'POSTED')
                        returning created_at
                        """)
                .param("id", id)
                .param("key", idempotencyKey)
                .param("from", request.fromAccountId())
                .param("to", request.toAccountId())
                .param("amount", request.amountMinor())
                .param("currency", request.currency())
                .query(OffsetDateTime.class)
                .single();

        insertPosting(id, request.fromAccountId(), -request.amountMinor(), request.currency());
        insertPosting(id, request.toAccountId(), request.amountMinor(), request.currency());

        return new TransferView(id, request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                request.currency(), "POSTED", createdAt);
    }

    TransferView findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("""
                        select id, from_account_id, to_account_id, amount_minor, currency, status, created_at
                        from transfers where idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query(TransferView.class)
                .single();
    }

    private void validate(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new TransferRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, "SAME_ACCOUNT", "cannot transfer to the same account");
        }
        List<Account> found = accounts.findAll(request.fromAccountId(), request.toAccountId());
        if (found.size() != 2) {
            throw new TransferRejectedException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "one or both accounts do not exist");
        }
        for (Account account : found) {
            if (!account.currency().equals(request.currency())) {
                throw new TransferRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, "CURRENCY_MISMATCH",
                        "account " + account.id() + " holds " + account.currency() + ", not " + request.currency());
            }
        }
    }

    private void insertPosting(UUID transferId, long accountId, long amountMinor, String currency) {
        jdbc.sql("""
                        insert into postings (transfer_id, account_id, amount_minor, currency)
                        values (:transfer, :account, :amount, :currency)
                        """)
                .param("transfer", transferId)
                .param("account", accountId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .update();
    }
}
