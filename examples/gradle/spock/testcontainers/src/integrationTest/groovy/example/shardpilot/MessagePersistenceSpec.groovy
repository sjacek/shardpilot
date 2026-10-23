package example.shardpilot

import java.nio.charset.StandardCharsets
import java.sql.SQLException

/**
 * Small persistence/acknowledgment examples, not an atomic database/broker transaction or complete inbox.
 * The unique-key feature demonstrates rejection of a duplicate, not successful idempotent reprocessing.
 */
// Tables exist only in a runtime Testcontainers database, not an IDE-managed datasource.
@SuppressWarnings(['SqlNoDataSourceInspection', 'SqlResolve'])
class MessagePersistenceSpec extends AbstractInfrastructureSpec {
    /** Stores one delivered payload with JDBC autocommit before sending its manual AMQP acknowledgment. */
    def 'persists a received event before acknowledging it'() {
        when:
        def storedCodes = database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute('CREATE TABLE received_events (code text PRIMARY KEY)')
            }
            broker().withCloseable { brokerConnection ->
                brokerConnection.createChannel().withCloseable { channel ->
                    String queue = channel.queueDeclare().getQueue()
                    channel.confirmSelect()
                    channel.basicPublish('', queue, null, 'event-001'.getBytes(StandardCharsets.UTF_8))
                    channel.waitForConfirmsOrDie(10_000)
                    def message = receive(channel, queue)
                    connection.prepareStatement('INSERT INTO received_events VALUES (?)').withCloseable { insert ->
                        insert.setString(1, new String(message.body, StandardCharsets.UTF_8))
                        insert.executeUpdate() // JDBC autocommit completes before the broker acknowledgment.
                    }
                    // A crash between commit and ack can still redeliver; real consumers need idempotency.
                    channel.basicAck(message.envelope.deliveryTag, false)
                }
            }
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('SELECT code FROM received_events').withCloseable { rows ->
                    def found = []
                    while (rows.next()) found.add(rows.getString(1))
                    found
                }
            }
        }

        then:
        storedCodes == ['event-001']
    }

    /** Confirms constraint rejection; it does not acknowledge broker deliveries or recover duplicates. */
    def 'rejects a duplicate event identifier'() {
        when:
        database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute('CREATE TABLE event_identifiers (code text PRIMARY KEY)')
                statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')")
                statement.executeUpdate("INSERT INTO event_identifiers VALUES ('event-002')")
            }
        }

        then:
        // Spock checks the exception from the preceding when block after withCloseable unwinds resources.
        def failure = thrown(SQLException)
        // SQLSTATE is stable across localized PostgreSQL/driver error messages.
        failure.getSQLState() == '23505'
    }
}
