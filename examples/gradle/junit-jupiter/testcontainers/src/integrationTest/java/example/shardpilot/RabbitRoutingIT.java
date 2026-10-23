package example.shardpilot;

import com.rabbitmq.client.BuiltinExchangeType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real AMQP routing and acknowledgments without fixed queue/exchange identities shared across tests. */
class RabbitRoutingIT extends AbstractInfrastructureIT {
    @Test
    void routesAnEventToItsBoundQueue() throws Exception {
        try (var connection = broker(); var channel = connection.createChannel()) {
            // Server-named exclusive queues disappear with their owning connection.
            String queue = channel.queueDeclare().getQueue();
            String exchange = "example." + UUID.randomUUID();
            channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null);
            try {
                channel.queueBind(queue, exchange, "entry.created");
                // Publisher confirms acknowledge broker acceptance, not consumption of the message below.
                channel.confirmSelect();
                channel.basicPublish(exchange, "entry.created", null, "demo-entry".getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(10_000);
                var message = receive(channel, queue);
                assertEquals("demo-entry", new String(message.getBody(), StandardCharsets.UTF_8));
                assertEquals("entry.created", message.getEnvelope().getRoutingKey());
                channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            } finally {
                // Remove only this test's generated exchange, including when an assertion fails.
                channel.exchangeDelete(exchange);
            }
        }
    }

    @Test
    void redeliversANegativelyAcknowledgedMessage() throws Exception {
        try (var connection = broker(); var channel = connection.createChannel()) {
            String queue = channel.queueDeclare().getQueue();
            channel.confirmSelect();
            channel.basicPublish("", queue, null, "retry-once".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(10_000);
            var first = receive(channel, queue);
            // Requeue one delivery explicitly; this is broker behavior, not a ShardPilot test retry.
            channel.basicNack(first.getEnvelope().getDeliveryTag(), false, true);
            var redelivered = receive(channel, queue);
            assertTrue(redelivered.getEnvelope().isRedeliver());
            assertArrayEquals(first.getBody(), redelivered.getBody());
            channel.basicAck(redelivered.getEnvelope().getDeliveryTag(), false);
        }
    }
}
