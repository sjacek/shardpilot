package example.shardpilot

import com.rabbitmq.client.BuiltinExchangeType

import java.nio.charset.StandardCharsets

/** AMQP routing and explicit redelivery with server-named, exclusive, auto-deleted queues. */
class RabbitRoutingSpec extends AbstractInfrastructureSpec {
    def 'routes an event to its bound queue'() {
        when:
        def received = broker().withCloseable { connection ->
            connection.createChannel().withCloseable { channel ->
                String queue = channel.queueDeclare().getQueue()
                String exchange = 'example.' + UUID.randomUUID()
                channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null)
                try {
                    channel.queueBind(queue, exchange, 'entry.created')
                    channel.confirmSelect()
                    channel.basicPublish(exchange, 'entry.created', null, 'demo-entry'.getBytes(StandardCharsets.UTF_8))
                    channel.waitForConfirmsOrDie(10_000)
                    def message = receive(channel, queue)
                    channel.basicAck(message.envelope.deliveryTag, false)
                    [body: new String(message.body, StandardCharsets.UTF_8), routingKey: message.envelope.routingKey]
                } finally {
                    // This exchange belongs only to this feature; never purge a shared/global broker resource.
                    channel.exchangeDelete(exchange)
                }
            }
        }

        then:
        received == [body: 'demo-entry', routingKey: 'entry.created']
    }

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
