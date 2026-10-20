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
 * One fixture pair per worker JVM, independent of all other CI shards and Gradle workers.
 * Startup is lazy and failures propagate to the tests; missing Docker must not turn into a skipped suite.
 * Clients close after each test, while Ryuk retains responsibility for disposable container cleanup.
 */
final class TestInfrastructure {
    private TestInfrastructure() { }

    /**
     * First fixture access initializes this holder once; later classes keep the same live containers.
     * Do not put a per-class stop/close hook around them: that would invalidate subsequent test classes.
     */
    private static final class Worker {
        // Disposable credentials are scoped to these randomly mapped containers; never log them.
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(IntegrationTestImages.POSTGRES))
                .withDatabaseName("shardpilot")
                .withUsername("example")
                .withPassword("example");
        static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse(IntegrationTestImages.RABBITMQ))
                .withAdminUser("example")
                .withAdminPassword("example");

        static {
            // Both services are independent, so their startup may overlap. Ryuk owns worker-exit cleanup.
            // No @Container/@Testcontainers extension may stop these shared fixtures after one test class.
            Startables.deepStart(POSTGRES, RABBITMQ).join();
        }
    }

    /**
     * Creates a class-local database inside the worker-owned PostgreSQL, never on an operator datasource.
     * Databases live until that container is removed; no cross-class DROP/TRUNCATE cleanup is needed.
     *
     * @return JDBC URL selecting the new database with the container's existing connection options
     * @throws SQLException when creation fails; no fallback to an external datasource is attempted
     */
    static String createDatabase() throws SQLException {
        String database = "example_" + UUID.randomUUID().toString().replace("-", "");
        if (!database.matches("example_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid generated database identifier");
        }
        // CREATE DATABASE requires autocommit. Only our generated, validated identifier enters this DDL.
        try (var connection = DriverManager.getConnection(Worker.POSTGRES.getJdbcUrl(),
                Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }
        String url = Worker.POSTGRES.getJdbcUrl();
        // Replace only the database path, retaining mapped host/port and any JDBC query parameters.
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        return address.substring(0, address.lastIndexOf('/') + 1) + database + options;
    }

    /** Opens a caller-owned connection to a URL previously allocated by {@link #createDatabase()}. */
    static java.sql.Connection openDatabase(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword());
    }

    /**
     * Opens a caller-owned AMQP connection using the started container's mapped endpoint and credentials.
     * Connection names are diagnostic labels, not shared queues or fixed Docker container names.
     */
    static com.rabbitmq.client.Connection openBroker() throws IOException, TimeoutException {
        var factory = new ConnectionFactory();
        factory.setHost(Worker.RABBITMQ.getHost());
        factory.setPort(Worker.RABBITMQ.getAmqpPort());
        factory.setUsername(Worker.RABBITMQ.getAdminUsername());
        factory.setPassword(Worker.RABBITMQ.getAdminPassword());
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        // A disappearing test broker should fail promptly, not be hidden by background reconnection.
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection("shardpilot-example");
    }
}
