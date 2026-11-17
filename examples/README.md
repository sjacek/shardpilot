# ShardPilot examples

Examples are grouped first by build tool, then by framework/scenario.

| Build tool | Examples | Run from the repository root |
| --- | --- | --- |
| [Gradle](gradle/README.md) | JUnit Jupiter, Spock and TestNG with PostgreSQL/RabbitMQ Testcontainers. | `./gradlew -p examples/gradle/junit-jupiter/testcontainers integrationTest` |
| [Maven](maven/README.md) | Surefire/Failsafe with JUnit Jupiter and a parallel multi-module reactor. | `./mvnw -f examples/maven/junit-jupiter/pom.xml verify` after local plugin installation. |

The Gradle scenarios consume the checkout with an included build. Testcontainers scenarios require
Docker; the [duration-history scenario](gradle/junit-jupiter/duration-history/README.md) does not.
Maven examples consume `shardpilot-maven-plugin` from a Maven repository and
need no Docker. Each example keeps its own compiled classes, manifests and test reports.

[Consumer CI examples](ci/README.md) show three-node GitLab pipelines, separate coverage/history
gates and report paths that remain distinct after artifact collection.

See the tool-specific indexes for prerequisites, shard commands, local development and IDE import.
The [documentation index](../docs/README.md) describes the supported contracts and verification scope.

Development [Gradle](gradle/junit-jupiter/selection-workflow/README.md) and
[Maven](maven/selection-workflow/README.md) workflow examples run a common selection on two native
workers and verify the original results and compiled inputs. See the
[complete guide](../docs/selection-migration.md) for commands and trust boundaries.

Development impact-selection examples use the same v2 policy for
[Gradle](gradle/junit-jupiter/selection-impact/README.md) and
[Maven](maven/selection-impact/README.md): directly changed tests, transitive module consumers
and scoped JSON/YAML contract changes, with no consumer selector scripts.
