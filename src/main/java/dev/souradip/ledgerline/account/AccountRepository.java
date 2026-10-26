package dev.souradip.ledgerline.account;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AccountView create(String currency) {
        long id = jdbc.sql("insert into accounts (kind, currency) values ('CUSTOMER', :currency) returning id")
                .param("currency", currency)
                .query(Long.class)
                .single();
        jdbc.sql("insert into balances (account_id, currency) values (:id, :currency)")
                .param("id", id)
                .param("currency", currency)
                .update();
        return new AccountView(id, "CUSTOMER", currency, 0, 0);
    }

    public Optional<AccountView> find(long id) {
        return jdbc.sql("""
                        select a.id, a.kind, a.currency, b.balance_minor, b.version
                        from accounts a join balances b on b.account_id = a.id
                        where a.id = :id
                        """)
                .param("id", id)
                .query(AccountView.class)
                .optional();
    }

    public List<Account> findAll(long first, long second) {
        return jdbc.sql("select id, kind, currency from accounts where id in (:first, :second)")
                .param("first", first)
                .param("second", second)
                .query(Account.class)
                .list();
    }

    public long treasuryFor(String currency) {
        return jdbc.sql("select id from accounts where kind = 'TREASURY' and currency = :currency")
                .param("currency", currency)
                .query(Long.class)
                .single();
    }
}
