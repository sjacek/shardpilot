package example.shardpilot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal broker-to-database flow, not an atomic cross-service transaction or a complete inbox/outbox.
 * The separate duplicate-key test demonstrates a database constraint, not an idempotent consumer policy.
 */
class MessagePersistenceIT extends AbstractInfrastructureIT {
    @Test
    void persistsAReceivedEventBeforeAcknowledgingIt() throws Exception {
        try (var brokerConnection = broker(); var channel = brokerConnection.createChannel();
             var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE received_events (code text PRIMARY KEY)");
            String queue = channel.queueDeclare().getQueue();
            channel.confirmSelect();
            channel.basicPublish("", queue, null, "event-001".getBytes(StandardCharsets.UTF_8));
            // Confirm publication before checking consumption; the database commit is a separate boundary.
            channel.waitForConfirmsOrDie(10_000);
            var message = receive(channel, queue);
            try (var insert = connection.prepareStatement("INSERT INTO received_events VALUES (?)")) {
                insert.setString(1, new String(message.getBody(), StandardCharsets.UTF_8));
                assertEquals(1, insert.executeUpdate()); // Autocommit finishes before the broker acknowledgment.
            }
            // A crash between commit and ack could still redeliver; production consumers need idempotency.
            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            try (var rows = statement.executeQuery("SELECT code FROM received_events")) {
                assertTrue(rows.next());
                assertEquals("event-001", rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void rejectsADuplicateEventIdentifier() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE event_identifiers (code text PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')");
            // Assert PostgreSQL's stable SQLSTATE, not a localized server/driver error message.
            var duplicate = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')"));
            assertEquals("23505", duplicate.getSQLState());
        }
    }
}
