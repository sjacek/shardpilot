package example.shardpilot

import groovy.transform.CompileStatic

/** Public fixture images; the build forwards explicit overrides identically to every shard JVM. */
@CompileStatic
final class IntegrationTestImages {
    // This major tag can move; choose a verified digest for immutable CI image contents.
    static final String POSTGRES = System.getProperty('example.postgresImage', 'postgres:18-alpine')
    static final String RABBITMQ = System.getProperty('example.rabbitmqImage', 'rabbitmq:4.3.6-management-alpine')

    private IntegrationTestImages() { }
}
