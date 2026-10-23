package example.shardpilot

import com.rabbitmq.client.BuiltinExchangeType

import java.nio.charset.StandardCharsets

/** AMQP routing and explicit redelivery with server-named, exclusive, auto-deleted queues. */
class RabbitRoutingSpec extends AbstractInfrastructureSpec {
    /** Verifies a direct-exchange binding with resources owned by one feature invocation. */
    def 'routes an event to its bound queue'() {
        when:
        def received = broker().withCloseable { connection ->
            connection.createChannel().withCloseable { channel ->
                // No-argument declaration creates a server-named, exclusive, auto-deleted queue.
                String queue = channel.queueDeclare().getQueue()
                String exchange = 'example.' + UUID.randomUUID()
                channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null)
                try {
                    channel.queueBind(queue, exchange, 'entry.created')
                    // Publisher confirmation is broker acceptance, not consumption or successful processing.
                    channel.confirmSelect()
                    channel.basicPublish(exchange, 'entry.created', null, 'demo-entry'.getBytes(StandardCharsets.UTF_8))
                    channel.waitForConfirmsOrDie(10_000)
                    def message = receive(channel, queue)
                    // Acknowledge only this delivery on its receiving channel, not a batch of pending messages.
                    channel.basicAck(message.envelope.deliveryTag, false)
                    [body: new String(message.body, StandardCharsets.UTF_8), routingKey: message.envelope.routingKey]
                } finally {
                    // This exchange belongs only to this feature; never purge a shared/global broker resource.
                    channel.exchangeDelete(exchange)
                }
            }
        }

        then:
        // The connection and channel are closed; compare a detached snapshot of the delivery metadata.
        received == [body: 'demo-entry', routingKey: 'entry.created']
    }

    /**
     * Exercises one explicit nack/requeue/ack cycle without a Spock retry extension or ShardPilot retry.
     * Redelivery metadata and payload preservation establish broker behavior, not consumer idempotency.
     */
    def 'redelivers a negatively acknowledged message'() {
        when:
        def received = broker().withCloseable { connection ->
            connection.createChannel().withCloseable { channel ->
                String queue = channel.queueDeclare().getQueue()
                channel.confirmSelect()
                channel.basicPublish('', queue, null, 'retry-once'.getBytes(StandardCharsets.UTF_8))
                channel.waitForConfirmsOrDie(10_000)
                def first = receive(channel, queue)
                // Broker-level requeue is intentional here; ShardPilot never retries a failed feature.
                channel.basicNack(first.envelope.deliveryTag, false, true)
                def redelivered = receive(channel, queue)
                channel.basicAck(redelivered.envelope.deliveryTag, false)
                [body: new String(redelivered.body, StandardCharsets.UTF_8), redelivered: redelivered.envelope.isRedeliver()]
            }
        }

        then:
        received == [body: 'retry-once', redelivered: true]
    }
}
