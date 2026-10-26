package dev.souradip.ledgerline.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event row in the caller's transaction.
 *
 * <p>Publishing to Kafka directly after the database commit is a dual write: if the process dies
 * between the two, the transfer happened and nobody downstream ever hears about it. Writing
 * the event to a table in the same transaction makes the two atomic, and the relay delivers it
 * later.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String eventType, String messageKey, Object payload) {
        jdbc.sql("""
                        insert into outbox (event_id, event_type, message_key, payload)
                        values (:eventId, :type, :key, :payload)
                        """)
                .param("eventId", eventId)
                .param("type", eventType)
                .param("key", messageKey)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
