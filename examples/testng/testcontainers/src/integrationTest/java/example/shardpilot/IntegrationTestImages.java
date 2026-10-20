package example.shardpilot;

/**
 * Public fixture images resolved once per worker, with identical explicit overrides across CI shards.
 * The build forwards {@code -Pexample.postgresImage} and {@code -Pexample.rabbitmqImage} as JVM properties;
 * no application deployment configuration or credentials are read here.
 */
final class IntegrationTestImages {
    // A moving major tag is convenient locally; pin a verified digest for immutable CI images.
    static final String POSTGRES = System.getProperty("example.postgresImage", "postgres:18-alpine");
    // The management variant supplies the endpoints expected by RabbitMQContainer's readiness checks.
    static final String RABBITMQ = System.getProperty("example.rabbitmqImage", "rabbitmq:4.3.6-management-alpine");

    private IntegrationTestImages() { }
}
