# Gradle examples

Runnable examples are grouped by test framework, then by scenario: `examples/gradle/<framework>/<scenario>/`.
Each scenario is an independent Gradle consumer build, not a subproject of the plugin build.

| Framework | Example | Status |
| --- | --- | --- |
| JUnit Jupiter | [PostgreSQL and RabbitMQ with Testcontainers](junit-jupiter/testcontainers/README.md) | Available |
| JUnit Jupiter | [Duration-history refresh without Docker](junit-jupiter/duration-history/README.md) | Available |
| Spock | [PostgreSQL and RabbitMQ with Testcontainers](spock/testcontainers/README.md) | Available |
| TestNG | [PostgreSQL and RabbitMQ with Testcontainers](testng/testcontainers/README.md) | Available |

## Run an example

Use the repository's Gradle Wrapper and select the scenario directory from the ShardPilot root:

```shell
./gradlew -p examples/gradle/junit-jupiter/testcontainers integrationTest
```

PowerShell:

```powershell
.\gradlew.bat -p examples/gradle/junit-jupiter/testcontainers integrationTest
```

All three Testcontainers examples require Docker. Follow their READMEs for shard selection and prerequisites;
the parent plugin's `check` does not automatically execute example builds.

## Import into IntelliJ IDEA

Opening the ShardPilot plugin build does not import the independent example builds. To work on an
example in the same IDE window, right-click its `build.gradle` and select **Import Gradle Project**,
or use **Link Gradle Project** in the Gradle tool window. Then sync the linked example. See
[JetBrains' Gradle project instructions](https://www.jetbrains.com/help/idea/work-with-gradle-projects.html).

Import the scenario directory, such as `examples/gradle/spock/testcontainers`, not its `src` directory.
Gradle supplies the `integrationTest` source roots and the example's own dependencies. The package
`example.shardpilot` is relative to `src/integrationTest/groovy` for Spock and
`src/integrationTest/java` for Jupiter and TestNG, not to the leaf `example/shardpilot` directory.

Examples share the parent Wrapper through the command-line invocations above; they do not contain
their own Wrapper files. If the IDE asks for a Gradle distribution, choose an installed distribution
matching the version in the parent's `gradle/wrapper/gradle-wrapper.properties`, and a compatible
Gradle JVM. Local `.idea` settings remain ignored by Git.

An unimported example can show `Package name mismatch` and unresolved Spock/Testcontainers symbols
even when `integrationTestClasses` compiles successfully. Import and sync the build instead of
removing its package declarations, marking the package directory as a source root, adding libraries
by hand or suppressing inspections. Docker is not needed for importing or compiling the example.

After moving an existing checkout to the `examples/gradle/` layout, check linked project paths in
the Gradle tool window. Relink an old `examples/spock/testcontainers` entry to
`examples/gradle/spock/testcontainers`, then use **Reload All Gradle Projects**. A link to the old
directory cannot supply source roots or dependencies for the moved files.

## Add another scenario

Place it under the appropriate framework directory and give its Gradle root project a unique name.
Resolve the local plugin with `includeBuild('../../../..')` in the scenario's `pluginManagement` block.
Keep its dependencies, reports and build outputs separate from the plugin and other scenarios.

Add an entry here and a focused README with runnable commands and resource requirements. A new
framework/scenario directory is not itself an additional compatibility claim; verify its build and
shard coverage first. See the [documentation index](../../docs/README.md) for the plugin's verified scope.
