package example.shardpilot;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.testng.annotations.BeforeClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * One generated database per concrete TestNG class, including all of its data-provider rows.
 * Only its URL is retained on the class instance; connections and transactions belong to invocations.
 * There is no static current-database field that another class could overwrite.
 */
public abstract class AbstractInfrastructureIT {
    private String jdbcUrl;

    /**
     * Inherited TestNG setup allocates once per concrete class, not once per data-provider row.
     * {@code alwaysRun} makes setup independent of group selection; it does not forgive a setup failure.
     * No class teardown stops the worker-owned containers needed by later classes.
     *
     * @throws SQLException when this class's isolated database cannot be created
     */
    @BeforeClass(alwaysRun = true)
    public final void prepareDatabase() throws SQLException {
        // Do not catch setup failures or convert missing Docker into SkipException.
        jdbcUrl = TestInfrastructure.createDatabase();
    }

    /**
     * Opens a fresh connection to the database allocated by {@link #prepareDatabase()}.
     * The caller closes it and explicitly manages transactions beyond JDBC's initial autocommit mode.
     *
     * @return a caller-owned connection, never a shared connection or an external application datasource
     * @throws SQLException when the fixture database cannot be reached
     */
    protected final Connection database() throws SQLException {
        return TestInfrastructure.openDatabase(jdbcUrl);
    }

    /**
     * Opens a method-owned connection to the worker's shared broker.
     * Closing it also removes its server-named exclusive queues; the broker itself remains available.
     *
     * @return a caller-owned AMQP connection with automatic recovery disabled
     * @throws Exception when the broker connection or handshake fails
     */
    protected static com.rabbitmq.client.Connection broker() throws Exception {
        return TestInfrastructure.openBroker();
    }

    /**
     * Polls a temporarily empty queue using a monotonic deadline, not a fixed startup sleep.
     * Broker I/O failures and interruption propagate instead of entering a retry loop.
     *
     * @param channel the channel that must later acknowledge or reject the returned delivery
     * @param queue this invocation's private queue, already declared by the caller
     * @return an unacknowledged delivery; the caller owns its processing and ack/nack decision
     * @throws Exception when a broker operation fails or the polling thread is interrupted
     * @throws AssertionError when no delivery arrives before the polling deadline
     */
    protected static GetResponse receive(Channel channel, String queue) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            GetResponse response = channel.basicGet(queue, false);
            // The caller must ack/nack this delivery on the same channel.
            if (response != null) return response;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No message arrived in the isolated example queue within 10 seconds");
    }
}
