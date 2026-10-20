# JUnit Jupiter with PostgreSQL and RabbitMQ

[All examples](../../README.md)

This standalone Gradle build demonstrates shared worker-local Testcontainers fixtures and isolated
test data. The plugin is resolved from the enclosing checkout through a composite build, not from
an unpublished Portal version.

From the ShardPilot repository root, run the full suite:

```shell
./gradlew -p examples/junit-jupiter/testcontainers integrationTest
```

Run each shard in a separate CI checkout, or run these commands sequentially locally:

```shell
./gradlew -p examples/junit-jupiter/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew -p examples/junit-jupiter/testcontainers integrationTest -Pshardpilot.count=2 -Pshardpilot.index=2
```

PowerShell equivalents, with quoted dotted Gradle properties:

```powershell
.\gradlew.bat -p examples/junit-jupiter/testcontainers integrationTest
.\gradlew.bat -p examples/junit-jupiter/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=1'
.\gradlew.bat -p examples/junit-jupiter/testcontainers integrationTest '-Pshardpilot.count=2' '-Pshardpilot.index=2'
```

A working Docker-compatible runtime is required; unavailable Docker
fails the tests instead of silently skipping them. No externally running PostgreSQL or RabbitMQ
instance is used. The parent plugin's ordinary `check` does not run this Docker-dependent example.

See the [complete guide](../../../docs/testcontainers-example.md) for startup, isolation, cleanup,
sample assignment, image overrides, reports and CI resource planning.
