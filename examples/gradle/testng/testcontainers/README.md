# TestNG with PostgreSQL and RabbitMQ

[All examples](../../README.md)

This independent Java build demonstrates ShardPilot with TestNG and real PostgreSQL/RabbitMQ
Testcontainers fixtures. A lazy worker-local holder starts both services once, while inherited
`@BeforeClass` allocates a separate database for each concrete class. Data-provider rows stay
with their class's shard and use distinct row identities.

From the ShardPilot repository root:

```shell
./gradlew -p examples/gradle/testng/testcontainers check
```

Run two isolated CI jobs, or execute these commands sequentially in one local checkout:

```shell
./gradlew -p examples/gradle/testng/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew -p examples/gradle/testng/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=2
```

PowerShell, with quoted dotted properties:

```powershell
.\gradlew.bat -p examples/gradle/testng/testcontainers check
.\gradlew.bat -p examples/gradle/testng/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=1'
.\gradlew.bat -p examples/gradle/testng/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=2'
```

Docker is required for execution. Startup failures are not converted into skipped tests, and
configuration failures retain Gradle/TestNG's failure policy. Use `integrationTestClasses` for
compilation without Docker. The parent plugin's `check` does not run this example.

For IDE editing, [import this standalone Gradle build](../../README.md#import-into-intellij-idea),
not just the enclosing plugin build. Do not add database credentials or a running datasource to
the IDE merely to inspect these disposable test tables.

See the [complete guide](../../../../docs/testng-testcontainers-example.md) for lifecycle, data-provider
ownership, images, reports and CI isolation. The [JUnit Jupiter](../../junit-jupiter/testcontainers/README.md)
and [Spock](../../spock/testcontainers/README.md) examples show equivalent scenarios with their own
framework lifecycles.
