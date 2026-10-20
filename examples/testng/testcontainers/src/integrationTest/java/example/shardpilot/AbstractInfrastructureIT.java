package example.shardpilot;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.testng.annotations.BeforeClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/** One generated database per concrete TestNG class, including all of its data-provider rows. */
public abstract class AbstractInfrastructureIT {
    private String jdbcUrl;

    /** TestNG inherits this configuration method; it runs before each concrete class's test methods. */
    @BeforeClass(alwaysRun = true)
    public final void prepareDatabase() throws SQLException {
        // Do not catch setup failures or convert missing Docker into SkipException.
        jdbcUrl = TestInfrastructure.createDatabase();
    }

    /** Connections and transactions belong to a method invocation, not the shared class fixture. */
    protected final Connection database() throws SQLException {
        return TestInfrastructure.openDatabase(jdbcUrl);
    }

    protected static com.rabbitmq.client.Connection broker() throws Exception {
        return TestInfrastructure.openBroker();
    }

    /** Bounded empty-queue polling, not a retry of failed tests or failed broker operations. */
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
