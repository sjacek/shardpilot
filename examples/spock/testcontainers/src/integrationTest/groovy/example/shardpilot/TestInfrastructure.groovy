package example.shardpilot

import com.rabbitmq.client.ConnectionFactory
import groovy.transform.CompileStatic
import org.testcontainers.lifecycle.Startables
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Worker-local infrastructure, independent of Spock's per-feature instances and other CI shards. */
@CompileStatic
final class TestInfrastructure {
    private TestInfrastructure() { }

    /** Lazy JVM/classloader initialization starts the pair once, not once for each specification. */
    private static final class Worker {
        // These disposable fixture credentials must never be replaced by an application datasource.
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(IntegrationTestImages.POSTGRES))
                .withDatabaseName('shardpilot')
                .withUsername('example')
                .withPassword('example')
        static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse(IntegrationTestImages.RABBITMQ))
                .withAdminUser('example')
                .withAdminPassword('example')

        static {
            // Independent services start concurrently. Missing Docker/startup failures fail the suite.
            // Ryuk cleans up on worker exit; cleanupSpec() must not stop another specification's fixtures.
            Startables.deepStart(POSTGRES, RABBITMQ).join()
        }
    }

    /** Allocate a specification-local database only inside the worker-owned disposable container. */
    static String createDatabase() throws SQLException {
        String database = 'example_' + UUID.randomUUID().toString().replace('-', '')
        if (!(database ==~ /example_[0-9a-f]{32}/)) {
            throw new IllegalArgumentException('Invalid generated database identifier')
        }
        // CREATE DATABASE uses autocommit; only a generated, validated identifier enters this DDL.
        DriverManager.getConnection(Worker.POSTGRES.getJdbcUrl(), Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword())
                .withCloseable { connection ->
                    connection.createStatement().withCloseable { statement ->
                        statement.execute('CREATE DATABASE "' + database + '"')
                    }
                }
        String url = Worker.POSTGRES.getJdbcUrl()
        int query = url.indexOf('?')
        String address = query < 0 ? url : url.substring(0, query)
        String options = query < 0 ? '' : url.substring(query)
        // Retain any container JDBC options and its randomly mapped host/port.
        address.substring(0, address.lastIndexOf('/') + 1) + database + options
    }

    /** The caller closes this connection; the URL comes from createDatabase(), not external configuration. */
    static Connection openDatabase(String jdbcUrl) throws SQLException {
        DriverManager.getConnection(jdbcUrl, Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword())
    }

    /** Clients/channels are feature-local even though their broker is worker-local. */
    static com.rabbitmq.client.Connection openBroker() {
        def factory = new ConnectionFactory()
        factory.setHost(Worker.RABBITMQ.getHost())
        factory.setPort(Worker.RABBITMQ.getAmqpPort())
        factory.setUsername(Worker.RABBITMQ.getAdminUsername())
        factory.setPassword(Worker.RABBITMQ.getAdminPassword())
        factory.setConnectionTimeout(10_000)
        factory.setHandshakeTimeout(10_000)
        // A lost fixture must fail the test, not be masked by a background reconnect loop.
        factory.setAutomaticRecoveryEnabled(false)
        factory.newConnection('shardpilot-spock-example')
    }
}
