package example.shardpilot;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Small persistence/acknowledgment scenarios, not an atomic database/broker transaction or complete inbox. */
// Tables are created at runtime inside Testcontainers, not in an IDE-managed datasource.
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class MessagePersistenceIT extends AbstractInfrastructureIT {
    @Test
    public void persistsAReceivedEventBeforeAcknowledgingIt() throws Exception {
        try (var brokerConnection = broker(); var channel = brokerConnection.createChannel();
             var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE received_events (code text PRIMARY KEY)");
            String queue = channel.queueDeclare().getQueue();
            channel.confirmSelect();
            channel.basicPublish("", queue, null, "event-001".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(10_000);
            var message = receive(channel, queue);
            try (var insert = connection.prepareStatement("INSERT INTO received_events VALUES (?)")) {
                insert.setString(1, new String(message.getBody(), StandardCharsets.UTF_8));
                assertEquals(insert.executeUpdate(), 1); // Autocommit completes before acknowledging delivery.
            }
            // A crash between commit and ack can still redeliver; real consumers need idempotency.
            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            try (var rows = statement.executeQuery("SELECT code FROM received_events")) {
                assertTrue(rows.next());
                assertEquals(rows.getString(1), "event-001");
                assertFalse(rows.next());
            }
        }
    }

    @Test
    public void rejectsADuplicateEventIdentifier() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE event_identifiers (code text PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')");
            // TestNG's expectThrows returns the exception for stable SQLSTATE assertions.
            var duplicate = expectThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')"));
            assertEquals(duplicate.getSQLState(), "23505");
        }
    }
}
