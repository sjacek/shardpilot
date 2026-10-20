# ShardPilot examples

Runnable examples are grouped by test framework, then by scenario: `examples/<framework>/<scenario>/`.
Each scenario is an independent Gradle consumer build, not a subproject of the plugin build.

| Framework | Example | Status |
| --- | --- | --- |
| JUnit Jupiter | [PostgreSQL and RabbitMQ with Testcontainers](junit-jupiter/testcontainers/README.md) | Available |
| Spock | Planned under `spock/` | Not implemented |
| TestNG | Planned under `testng/` | Not implemented |

## Run an example

Use the repository's Gradle Wrapper and select the scenario directory from the ShardPilot root:

```shell
./gradlew -p examples/junit-jupiter/testcontainers integrationTest
```

PowerShell:

```powershell
.\gradlew.bat -p examples/junit-jupiter/testcontainers integrationTest
```

The current example requires Docker. Follow its README for shard selection and prerequisites;
the parent plugin's `check` does not automatically execute example builds.

## Add another scenario

Place it under the appropriate framework directory and give its Gradle root project a unique name.
Resolve the local plugin with `includeBuild('../../..')` in the scenario's `pluginManagement` block.
Keep its dependencies, reports and build outputs separate from the plugin and other scenarios.

Add an entry here and a focused README with runnable commands and resource requirements. Framework
directories listed as planned are reserved naming conventions, not implemented examples or additional
compatibility claims. See the [documentation index](../docs/README.md) for the plugin's verified scope.
