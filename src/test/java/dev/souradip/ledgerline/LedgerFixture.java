package dev.souradip.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.souradip.ledgerline.account.AccountRepository;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import dev.souradip.ledgerline.locking.LockingMode;
import dev.souradip.ledgerline.transfer.TransferRequest;
import dev.souradip.ledgerline.transfer.TransferService;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Helpers for setting up accounts and checking that the books still balance. */
@TestComponent
public class LedgerFixture {

    private final AccountRepository accounts;
    private final TransferService transfers;
    private final JdbcClient jdbc;

    LedgerFixture(AccountRepository accounts, TransferService transfers, JdbcClient jdbc) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.jdbc = jdbc;
    }

    public long openAccount(long initialBalanceMinor) {
        long id = accounts.create("USD").id();
        if (initialBalanceMinor > 0) {
            StoredResponse funded = transfers.transfer(UUID.randomUUID().toString(),
                    new TransferRequest(accounts.treasuryFor("USD"), id, initialBalanceMinor, "USD"),
                    LockingMode.PESSIMISTIC);
            assertThat(funded.status()).isEqualTo(201);
        }
        return id;
    }

    public long balanceOf(long accountId) {
        return jdbc.sql("select balance_minor from balances where account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    public long clearingBalance(String currency) {
        return balanceOf(accounts.systemAccount("PAYOUT_CLEARING", currency));
    }

    public long transferCount() {
        return jdbc.sql("select count(*) from transfers").query(Long.class).single();
    }

    /**
     * The properties that must hold no matter what concurrency did:
     * money is neither created nor destroyed, every balance snapshot agrees with its postings,
     * no customer account is negative, and every transfer has exactly two postings.
     */
    public void assertBooksBalance() {
        long total = jdbc.sql("select coalesce(sum(amount_minor), 0) from postings").query(Long.class).single();
        assertThat(total).as("sum of all postings").isZero();

        long drifted = jdbc.sql("""
                        select count(*) from balances b
                        left join (select account_id, sum(amount_minor) as total from postings group by account_id) p
                          on p.account_id = b.account_id
                        where b.balance_minor <> coalesce(p.total, 0)
                        """)
                .query(Long.class)
                .single();
        assertThat(drifted).as("balances that disagree with their postings").isZero();

        long negative = jdbc.sql("""
                        select count(*) from balances b join accounts a on a.id = b.account_id
                        where a.kind = 'CUSTOMER' and b.balance_minor < 0
                        """)
                .query(Long.class)
                .single();
        assertThat(negative).as("negative customer balances").isZero();

        long malformed = jdbc.sql("""
                        select count(*) from transfers t
                        where (select count(*) from postings p where p.transfer_id = t.id) <> 2
                        """)
                .query(Long.class)
                .single();
        assertThat(malformed).as("transfers without exactly two postings").isZero();
    }
}
