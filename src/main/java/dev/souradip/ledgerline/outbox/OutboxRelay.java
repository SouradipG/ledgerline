package dev.souradip.ledgerline.outbox;

import dev.souradip.ledgerline.LedgerProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves outbox rows to Kafka.
 *
 * <p>Every instance runs a relay. {@code FOR UPDATE SKIP LOCKED} hands each instance a different
 * batch, so scaling out adds throughput instead of duplicate publishes. Without it, every
 * instance would read the same unpublished rows and send them all.
 *
 * <p>Delivery is at least once. If the process dies after Kafka acknowledged a batch but before
 * the {@code published_at} update commits, the rows are sent again on the next tick. Consumers
 * dedupe on event id, which is what makes that safe.
 *
 * <p>Trade off: with several relays, two batches can reach Kafka out of id order. Consumers here
 * apply commutative updates, so order does not matter. A consumer that needs strict per key
 * order would need a single relay per partition instead.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final LedgerProperties.Outbox config;

    /** Test hook that runs after Kafka acknowledged a batch and before it is marked published. */
    volatile Runnable afterSend = () -> {};

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, LedgerProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.config = properties.outbox();
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:200ms}")
    void drain() {
        try {
            while (publishBatch() == config.batchSize()) {
                // keep going while there is a backlog
            }
        } catch (RuntimeException e) {
            // Rows stay unpublished and are retried on the next tick.
            log.warn("outbox relay batch failed, will retry: {}", e.toString());
        }
    }

    int publishBatch() {
        Integer published = tx.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                            select id, event_id, event_type, message_key, payload from outbox
                            where published_at is null
                            order by id
                            limit :limit
                            for update skip locked
                            """)
                    .param("limit", config.batchSize())
                    .query(OutboxRow.class)
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }

            List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
            for (OutboxRow row : rows) {
                ProducerRecord<String, String> record = new ProducerRecord<>(config.topic(), row.messageKey(), row.payload());
                record.headers().add("event-id", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("event-type", row.eventType().getBytes(StandardCharsets.UTF_8));
                acks.add(kafka.send(record));
            }
            try {
                CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new))
                        .get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("kafka did not acknowledge outbox batch", e);
            }

            afterSend.run();

            jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                    .param("ids", rows.stream().map(OutboxRow::id).toList())
                    .update();
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    record OutboxRow(long id, UUID eventId, String eventType, String messageKey, String payload) {}
}
