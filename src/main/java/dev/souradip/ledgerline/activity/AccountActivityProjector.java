package dev.souradip.ledgerline.activity;

import dev.souradip.ledgerline.outbox.TransferPostedEvent;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the account_activity read model from transfer events.
 *
 * <p>Kafka delivers at least once: after a rebalance, a crash before the offset commit, or an
 * outbox relay retry, the same event arrives again. The dedupe row and the projection update
 * commit in one transaction, so each event is applied exactly once even when delivered twice.
 * Offsets are committed only after the listener returns, so a crash mid event means redelivery,
 * never loss.
 */
@Component
public class AccountActivityProjector {

    static final String CONSUMER = "account-activity";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final AtomicLong deliveries = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    AccountActivityProjector(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(topics = "${ledger.outbox.topic}", groupId = CONSUMER)
    void on(ConsumerRecord<String, String> record) {
        deliveries.incrementAndGet();
        TransferPostedEvent event = json.readValue(record.value(), TransferPostedEvent.class);
        tx.executeWithoutResult(status -> {
            int fresh = jdbc.sql("""
                            insert into processed_events (consumer, event_id) values (:consumer, :eventId)
                            on conflict do nothing
                            """)
                    .param("consumer", CONSUMER)
                    .param("eventId", event.eventId())
                    .update();
            if (fresh == 0) {
                duplicates.incrementAndGet();
                return;
            }
            apply(event.fromAccountId(), 0, 1, 0, event.amountMinor());
            apply(event.toAccountId(), 1, 0, event.amountMinor(), 0);
        });
    }

    private void apply(long accountId, int credits, int debits, long credited, long debited) {
        jdbc.sql("""
                        insert into account_activity (account_id, credits_count, debits_count, credited_minor, debited_minor)
                        values (:id, :credits, :debits, :credited, :debited)
                        on conflict (account_id) do update set
                          credits_count = account_activity.credits_count + excluded.credits_count,
                          debits_count = account_activity.debits_count + excluded.debits_count,
                          credited_minor = account_activity.credited_minor + excluded.credited_minor,
                          debited_minor = account_activity.debited_minor + excluded.debited_minor
                        """)
                .param("id", accountId)
                .param("credits", credits)
                .param("debits", debits)
                .param("credited", credited)
                .param("debited", debited)
                .update();
    }

    public long deliveries() {
        return deliveries.get();
    }

    public long duplicatesSkipped() {
        return duplicates.get();
    }
}
