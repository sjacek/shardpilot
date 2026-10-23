package example.shardpilot

import groovy.transform.CompileStatic

/**
 * Public fixture images resolved once per worker, with identical explicit overrides across CI shards.
 * The build forwards -Pexample.postgresImage and -Pexample.rabbitmqImage as JVM properties;
 * no application deployment configuration or credentials are read here.
 */
@CompileStatic
final class IntegrationTestImages {
    // This major tag can move; choose a verified digest for immutable CI image contents.
    static final String POSTGRES = System.getProperty('example.postgresImage', 'postgres:18-alpine')
    // The management variant supplies the endpoints expected by RabbitMQContainer's readiness checks.
    static final String RABBITMQ = System.getProperty('example.rabbitmqImage', 'rabbitmq:4.3.6-management-alpine')

    private IntegrationTestImages() { }
}
