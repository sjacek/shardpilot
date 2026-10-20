package example.shardpilot;

/**
 * Public fixture images, resolved once per worker without application deployment configuration.
 * The example build forwards matching {@code -Pexample.postgresImage}/{@code -Pexample.rabbitmqImage}
 * properties into this JVM; use identical overrides in all shards to keep their environments comparable.
 */
final class IntegrationTestImages {
    // This major-version tag can move; select a verified digest when CI requires immutable image contents.
    static final String POSTGRES = System.getProperty("example.postgresImage", "postgres:18-alpine");
    // The management image is compatible with RabbitMQContainer's exposed ports and readiness checks.
    static final String RABBITMQ = System.getProperty("example.rabbitmqImage", "rabbitmq:4.3.6-management-alpine");

    private IntegrationTestImages() { }
}
