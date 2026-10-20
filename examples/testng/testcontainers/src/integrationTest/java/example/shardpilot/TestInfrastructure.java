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
 */
final class TestInfrastructure {
    private TestInfrastructure() { }

    /** JVM initialization starts these services once even when several classes access the holder. */
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

    /** Allocate an isolated database inside the disposable worker-owned container, never externally. */
    static String createDatabase() throws SQLException {
        String database = "example_" + UUID.randomUUID().toString().replace("-", "");
        if (!database.matches("example_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid generated database identifier");
        }
        // CREATE DATABASE requires autocommit; only the generated, validated identifier enters DDL.
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

    /** The caller closes this connection; the URL must come from the class fixture's allocation. */
    static java.sql.Connection openDatabase(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, Worker.POSTGRES.getUsername(), Worker.POSTGRES.getPassword());
    }

    /** Open a method-owned broker connection; server-named exclusive queues disappear when it closes. */
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
