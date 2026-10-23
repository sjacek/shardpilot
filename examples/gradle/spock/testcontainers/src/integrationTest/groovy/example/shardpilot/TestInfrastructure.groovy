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

/**
 * Worker-local infrastructure, independent of Spock's per-feature instances and other CI shards.
 * Ryuk owns container cleanup on worker exit; configuration-cache reuse does not preserve live fixtures.
 */
@CompileStatic
final class TestInfrastructure {
    private TestInfrastructure() { }

    /**
     * JVM/classloader initialization starts the pair on first access and publishes the ready fixtures.
     * Loading image settings or compiling sources alone does not initialize this nested holder.
     */
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

    /**
     * Allocates a specification-local database only inside the worker-owned disposable container.
     * Databases live until container removal; no cross-specification DROP/TRUNCATE cleanup is needed.
     *
     * @return a JDBC URL selecting the generated database with the mapped endpoint and options intact
     * @throws SQLException when allocation fails; no fallback to an external datasource is attempted
     */
    static String createDatabase() throws SQLException {
        String database = 'example_' + UUID.randomUUID().toString().replace('-', '')
        if (!(database ==~ /example_[0-9a-f]{32}/)) {
            throw new IllegalArgumentException('Invalid generated database identifier')
        }
        // JDBC parameters cannot represent SQL identifiers; only this generated, validated name enters DDL.
        // CREATE DATABASE uses its own autocommit connection, outside any feature's transaction.
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

    /**
     * Opens a fresh connection using container-owned credentials, without pooling or changing autocommit.
     *
     * @param jdbcUrl the URL previously allocated by createDatabase(), not operator configuration
     * @return a connection whose resource and transaction lifetime belongs to the caller
     * @throws SQLException when the generated database cannot be reached
     */
    static Connection openDatabase(String jdbcUrl) throws SQLException {
        DriverManager.getConnection(jdbcUrl, Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword())
    }

    /**
     * Opens a feature-owned connection using the ready container's mapped endpoint and credentials.
     * The connection name is a diagnostic label, not a fixed container or queue identity.
     *
     * @return a fresh AMQP connection; callers also close the channels they create from it
     */
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
