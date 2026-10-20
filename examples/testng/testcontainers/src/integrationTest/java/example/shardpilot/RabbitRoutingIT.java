package example.shardpilot;

import com.rabbitmq.client.BuiltinExchangeType;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** AMQP routing and explicit redelivery, with no fixed queue names or cross-method dependencies. */
public class RabbitRoutingIT extends AbstractInfrastructureIT {
    @Test
    public void routesAnEventToItsBoundQueue() throws Exception {
        try (var connection = broker(); var channel = connection.createChannel()) {
            String queue = channel.queueDeclare().getQueue();
            String exchange = "example." + UUID.randomUUID();
            channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null);
            try {
                channel.queueBind(queue, exchange, "entry.created");
                // Publisher confirms acknowledge broker acceptance, not consumer processing.
                channel.confirmSelect();
                channel.basicPublish(exchange, "entry.created", null, "demo-entry".getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(10_000);
                var message = receive(channel, queue);
                assertEquals(new String(message.getBody(), StandardCharsets.UTF_8), "demo-entry");
                assertEquals(message.getEnvelope().getRoutingKey(), "entry.created");
                channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            } finally {
                // Clean up only this method's exchange, including when an assertion fails.
                channel.exchangeDelete(exchange);
            }
        }
    }

    @Test
    public void redeliversANegativelyAcknowledgedMessage() throws Exception {
        try (var connection = broker(); var channel = connection.createChannel()) {
            String queue = channel.queueDeclare().getQueue();
            channel.confirmSelect();
            channel.basicPublish("", queue, null, "retry-once".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(10_000);
            var first = receive(channel, queue);
            // Explicit broker requeue is not an automatic retry of a TestNG test by ShardPilot.
            channel.basicNack(first.getEnvelope().getDeliveryTag(), false, true);
            var redelivered = receive(channel, queue);
            assertTrue(redelivered.getEnvelope().isRedeliver());
            assertEquals(redelivered.getBody(), first.getBody());
            channel.basicAck(redelivered.getEnvelope().getDeliveryTag(), false);
        }
    }
}
