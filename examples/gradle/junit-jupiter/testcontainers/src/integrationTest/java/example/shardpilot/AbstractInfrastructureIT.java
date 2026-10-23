package example.shardpilot;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Shared services, but one database per concrete test class; methods keep independent tables/queues.
 * PER_CLASS permits instance-level BeforeAll setup and lets nested tests use the enclosing fixture.
 * This is JUnit fixture state, not a global datasource shared between CI shards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractInfrastructureIT {
    private String jdbcUrl;

    /** Allocate once per concrete class, after worker-local services are ready and before test methods run. */
    @BeforeAll
    void prepareDatabase() throws SQLException {
        jdbcUrl = TestInfrastructure.createDatabase();
    }

    /** Each caller closes its own connection and explicitly controls any transaction beyond autocommit. */
    protected final Connection database() throws SQLException {
        return TestInfrastructure.openDatabase(jdbcUrl);
    }

    /** Each caller owns its AMQP connection/channels; exclusive queues disappear on connection closure. */
    protected final com.rabbitmq.client.Connection broker() throws Exception {
        return TestInfrastructure.openBroker();
    }

    /**
     * Waits for asynchronous delivery without relying on an arbitrary fixed startup sleep.
     * The returned delivery is unacknowledged: the test must ack/nack it on this same channel.
     * Only an empty queue is polled; channel failures and interruption propagate without retrying a test.
     */
    protected static GetResponse receive(Channel channel, String queue) throws Exception {
        // A monotonic deadline is unaffected by wall-clock adjustments while the broker is delivering.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            GetResponse response = channel.basicGet(queue, false);
            if (response != null) return response;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No message arrived in the isolated example queue within 10 seconds");
    }
}
