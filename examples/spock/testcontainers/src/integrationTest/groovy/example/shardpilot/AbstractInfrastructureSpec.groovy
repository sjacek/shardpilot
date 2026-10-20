package example.shardpilot

import com.rabbitmq.client.Channel
import com.rabbitmq.client.GetResponse
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.util.concurrent.TimeUnit

/** Shared services, with one database per concrete specification rather than per feature iteration. */
abstract class AbstractInfrastructureSpec extends Specification {
    // Spock creates new feature/iteration instances; retain only their common database URL here.
    @Shared
    private String jdbcUrl

    def setupSpec() {
        jdbcUrl = TestInfrastructure.createDatabase()
    }

    /** The feature owns connection/transaction lifetime; no connection is stored in @Shared state. */
    protected final Connection database() {
        TestInfrastructure.openDatabase(jdbcUrl)
    }

    protected static com.rabbitmq.client.Connection broker() {
        TestInfrastructure.openBroker()
    }

    /** Empty queues are polled briefly; channel failures/interruption propagate without retrying a feature. */
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
