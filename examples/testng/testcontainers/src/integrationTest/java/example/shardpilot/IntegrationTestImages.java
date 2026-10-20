package example.shardpilot;

/** Public fixture images, with the same explicit build-property overrides in every shard JVM. */
final class IntegrationTestImages {
    // A moving major tag is convenient locally; pin a verified digest for immutable CI images.
    static final String POSTGRES = System.getProperty("example.postgresImage", "postgres:18-alpine");
    static final String RABBITMQ = System.getProperty("example.rabbitmqImage", "rabbitmq:4.3.6-management-alpine");

    private IntegrationTestImages() { }
}
