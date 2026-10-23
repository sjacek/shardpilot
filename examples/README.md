# ShardPilot examples

Examples are grouped first by build tool, then by framework/scenario.

| Build tool | Examples | Run from the repository root |
| --- | --- | --- |
| [Gradle](gradle/README.md) | JUnit Jupiter, Spock and TestNG with PostgreSQL/RabbitMQ Testcontainers. | `./gradlew -p examples/gradle/junit-jupiter/testcontainers integrationTest` |
| [Maven](maven/README.md) | Surefire/Failsafe with JUnit Jupiter and a parallel multi-module reactor. | `./mvnw -f examples/maven/junit-jupiter/pom.xml verify` after local plugin installation. |

The Gradle scenarios consume the checkout with an included build and require Docker to run their
integration tests. Maven examples consume `shardpilot-maven-plugin` from a Maven repository and
need no Docker. Each example keeps its own compiled classes, manifests and test reports.

See the tool-specific indexes for prerequisites, shard commands, local development and IDE import.
The [documentation index](../docs/README.md) describes the supported contracts and verification scope.
