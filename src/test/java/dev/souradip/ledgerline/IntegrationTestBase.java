package dev.souradip.ledgerline;

import dev.souradip.ledgerline.simulator.PaymentProviderSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every test runs against real Postgres and real Kafka. H2 and other embedded databases lock and
 * isolate differently, so a concurrency test that passes there proves very little.
 *
 * <p>Containers and the simulated payment provider are started once and shared by all classes.
 * Timeouts and retry delays are shortened so the failure scenarios run in seconds.
 */
@SpringBootTest(properties = {
        "ledger.bulkhead.max-wait=60s",
        "ledger.optimistic.max-attempts=10",
        "ledger.outbox.poll-interval=100ms",
        "ledger.payouts.poll-interval=100ms",
        "ledger.payouts.lease=5s",
        "ledger.payouts.max-attempts=6",
        "ledger.payouts.retry-base=100ms",
        "ledger.payouts.retry-max=400ms",
        "ledger.provider.read-timeout=500ms"
})
@Import(LedgerFixture.class)
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    protected static final PaymentProviderSimulator PROVIDER = new PaymentProviderSimulator().start(0);

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("ledger.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected LedgerFixture ledger;

    @BeforeEach
    void cleanDatabase() {
        PROVIDER.reset();
        jdbc.sql("truncate postings, transfers, idempotency_keys, outbox, processed_events, account_activity, payouts, recon_exceptions")
                .update();
        jdbc.sql("delete from balances where account_id in (select id from accounts where kind = 'CUSTOMER')").update();
        jdbc.sql("delete from accounts where kind = 'CUSTOMER'").update();
        jdbc.sql("update balances set balance_minor = 0, version = 0").update();
    }
}
