package example.shardpilot;

import com.rabbitmq.client.ConnectionFactory;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * A lazy service pair owned by one test worker, not by a single TestNG class or method.
 * Each shard has independent fixtures; Ryuk removes them when the worker exits.
 * Configuration-cache reuse does not preserve these running containers across Gradle invocations.
 */
final class TestInfrastructure {
    private TestInfrastructure() { }

    /**
     * JVM/classloader initialization starts the pair on first access and publishes the ready fixtures.
     * Loading image settings or compiling test sources alone does not initialize this nested holder.
     */
    private static final class Worker {
        // Disposable credentials and randomly mapped ports never come from an application datasource.
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(IntegrationTestImages.POSTGRES))
                .withDatabaseName("shardpilot")
                .withUsername("example")
                .withPassword("example");
        static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse(IntegrationTestImages.RABBITMQ))
                .withAdminUser("example")
                .withAdminPassword("example");

        static {
            // Startup overlaps, but tests wait for both services; startup failures remain failures.
            // No @AfterClass hook may stop containers needed by a subsequent class in this worker.
            Startables.deepStart(POSTGRES, RABBITMQ).join();
        }
    }

    /**
     * Allocates a class-local database only inside this worker's disposable PostgreSQL container.
     * Databases remain until container removal; no cross-class DROP/TRUNCATE cleanup is necessary.
     *
     * @return a JDBC URL selecting the generated database while retaining the mapped endpoint and options
     * @throws SQLException when allocation fails; no fallback to a real application datasource is attempted
     */
    static String createDatabase() throws SQLException {
        String database = "example_" + UUID.randomUUID().toString().replace("-", "");
        if (!database.matches("example_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid generated database identifier");
        }
        // JDBC parameters cannot represent SQL identifiers. Only our generated, validated name enters DDL.
        // CREATE DATABASE runs on its own autocommit connection, outside any test method's transaction.
        try (var connection = DriverManager.getConnection(Worker.POSTGRES.getJdbcUrl(),
                Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }
        String url = Worker.POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        // Preserve the container's mapped endpoint and JDBC options when selecting the new database.
        return address.substring(0, address.lastIndexOf('/') + 1) + database + options;
    }

    /**
     * Opens a fresh connection using container-owned credentials, without pooling or changing autocommit.
     *
     * @param jdbcUrl a URL previously allocated by {@link #createDatabase()}, not operator configuration
     * @return a connection whose resource and transaction lifetime belongs to the caller
     * @throws SQLException when the generated database cannot be reached
     */
    static java.sql.Connection openDatabase(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword());
    }

    /**
     * Opens a method-owned connection using the ready container's mapped endpoint and credentials.
     * Server-named exclusive queues disappear on connection closure; this does not stop the shared broker.
     * The connection name is a diagnostic label, not a fixed container or queue identity.
     *
     * @return a fresh AMQP connection; callers also close the channels they create from it
     * @throws IOException when the broker cannot be contacted or the connection fails
     * @throws TimeoutException when the AMQP connection handshake times out
     */
    static com.rabbitmq.client.Connection openBroker() throws IOException, TimeoutException {
        var factory = new ConnectionFactory();
        factory.setHost(Worker.RABBITMQ.getHost());
        factory.setPort(Worker.RABBITMQ.getAmqpPort());
        factory.setUsername(Worker.RABBITMQ.getAdminUsername());
        factory.setPassword(Worker.RABBITMQ.getAdminPassword());
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        // Lost infrastructure must fail promptly, not be hidden by background reconnect attempts.
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection("shardpilot-testng-example");
    }
}
