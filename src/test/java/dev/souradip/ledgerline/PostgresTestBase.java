package dev.souradip.ledgerline;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every test runs against real Postgres. H2 and other embedded databases lock and isolate
 * differently, so a concurrency test that passes there proves very little.
 *
 * <p>One container is shared by all test classes and started once.
 */
@SpringBootTest(properties = {
        "ledger.bulkhead.max-wait=60s",
        "ledger.optimistic.max-attempts=10"
})
@Import(LedgerFixture.class)
public abstract class PostgresTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected LedgerFixture ledger;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("truncate postings, transfers, idempotency_keys").update();
        jdbc.sql("delete from balances where account_id in (select id from accounts where kind = 'CUSTOMER')").update();
        jdbc.sql("delete from accounts where kind = 'CUSTOMER'").update();
        jdbc.sql("update balances set balance_minor = 0, version = 0").update();
    }
}
