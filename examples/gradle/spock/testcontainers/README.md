# Spock with PostgreSQL and RabbitMQ

[All examples](../../README.md)

This standalone Groovy build demonstrates ShardPilot with Spock, a data-driven feature and real
PostgreSQL/RabbitMQ Testcontainers fixtures. A lazy worker-local holder starts the containers once;
`setupSpec()` allocates a database whose URL is `@Shared` within each concrete specification.

From the ShardPilot repository root:

```shell
./gradlew -p examples/gradle/spock/testcontainers integrationTest
```

Run each shard in a separate CI workspace, or run these commands sequentially locally:

```shell
./gradlew -p examples/gradle/spock/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew -p examples/gradle/spock/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=2
```

PowerShell equivalents, with quoted dotted properties:

```powershell
.\gradlew.bat -p examples/gradle/spock/testcontainers integrationTest
.\gradlew.bat -p examples/gradle/spock/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=1'
.\gradlew.bat -p examples/gradle/spock/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=2'
```

Docker is required for execution, and failures are not converted into skipped specifications.
For compilation without Docker, use `integrationTestClasses` instead of `integrationTest`.
The parent plugin's `check` does not automatically run this example.

For IDE editing, [import this standalone Gradle build](../../README.md#import-into-intellij-idea);
opening only the plugin build does not load this example's source roots or dependencies.

See the [complete guide](../../../../docs/spock-testcontainers-example.md) for lifecycle, data-driven
ownership, configuration, images, reports and isolation. The [JUnit Jupiter example](../../junit-jupiter/testcontainers/README.md)
shows the equivalent JDBC/AMQP scenarios with a different framework lifecycle.
