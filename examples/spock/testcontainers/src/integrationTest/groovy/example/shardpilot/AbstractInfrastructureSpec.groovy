package example.shardpilot

import com.rabbitmq.client.Channel
import com.rabbitmq.client.GetResponse
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.util.concurrent.TimeUnit

/**
 * Shared services, with one database per concrete specification rather than per feature iteration.
 * Spock creates separate feature/iteration instances, so only the database URL is shared across them.
 * Connections and transactions are never stored in shared state or reused by another specification.
 */
abstract class AbstractInfrastructureSpec extends Specification {
    // Spock creates new feature/iteration instances; retain only their common database URL here.
    @Shared
    private String jdbcUrl

    /**
     * Spock invokes this inherited fixture once for each concrete specification before its features.
     * Allocation may initialize the worker's lazy service pair; failures propagate rather than skip tests.
     * No cleanupSpec() stops that pair because later specifications in the worker still need it.
     */
    def setupSpec() {
        jdbcUrl = TestInfrastructure.createDatabase()
    }

    /**
     * Opens a fresh connection to the database allocated by setupSpec().
     * The caller closes it with withCloseable and owns any transaction beyond initial JDBC autocommit.
     *
     * @return a caller-owned connection, not a shared connection or an application datasource
     */
    protected final Connection database() {
        TestInfrastructure.openDatabase(jdbcUrl)
    }

    /**
     * Opens a feature/iteration-owned connection to the worker's shared broker.
     * Closing it removes its exclusive queues, but leaves the broker available for later features.
     *
     * @return a caller-owned AMQP connection with automatic recovery disabled
     */
    protected static com.rabbitmq.client.Connection broker() {
        TestInfrastructure.openBroker()
    }

    /**
     * Polls a temporarily empty queue using a monotonic deadline, not a fixed startup sleep.
     * Channel failures and interruption propagate instead of retrying a failed feature or broker operation.
     *
     * @param channel the receiving channel that must later acknowledge or reject the delivery
     * @param queue the caller's already declared private queue
     * @return an unacknowledged delivery whose processing and ack/nack decision belong to the caller
     * @throws AssertionError when no delivery arrives before the polling deadline
     */
    protected static GetResponse receive(Channel channel, String queue) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            GetResponse response = channel.basicGet(queue, false)
            // The feature must ack/nack this delivery on the same channel after its assertions/work.
            if (response != null) return response
            TimeUnit.MILLISECONDS.sleep(20)
        } while (System.nanoTime() < deadline)
        throw new AssertionError('No message arrived in the isolated example queue within 10 seconds')
    }
}
