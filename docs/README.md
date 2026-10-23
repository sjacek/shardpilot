# ShardPilot documentation

ShardPilot splits the classes of an opted-in Gradle `Test` task between independent executions.
Each execution calculates ownership locally from the same inputs. There is no coordinator service
or replacement test engine.

This checkout documents the upcoming 0.2.1 release. The published
[0.1.0 release](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.1.0) supports sharding
without the new optional test-process block. Use the
[root README](../README.md#getting-started) to apply it from the Gradle Plugin Portal, or the
[local development setup](../README.md#local-plugin-development) to test a checkout explicitly.
The [0.2.1 release notes](releases/0.2.1.md) contain migration examples and the publication checklist.

## Guides

| Guide | Read it when you need to… |
| --- | --- |
| [Configuration](configuration.md) | Opt in tasks, supply history, use Groovy/Kotlin DSL or configure multiple projects. |
| [Architecture and assignment](architecture.md) | Understand components, class-family ownership, balancing and task inputs. |
| [CI integration](ci.md) | Run all shards with consistent inputs, isolated workspaces and separate reports. |
| [PostgreSQL and RabbitMQ example](testcontainers-example.md) | Run a JUnit Jupiter integration suite with shared worker-local Testcontainers fixtures and isolated test data. |
| [Spock with PostgreSQL and RabbitMQ](spock-testcontainers-example.md) | Run specifications and data-table iterations with worker-local containers and specification-local databases. |
| [TestNG with PostgreSQL and RabbitMQ](testng-testcontainers-example.md) | Run class fixtures and data-provider rows with the native TestNG adapter and worker-local containers. |
| [Testing and compatibility](testing.md) | Verify changes and understand what the existing tests actually cover. |
| [Troubleshooting](troubleshooting.md) | Diagnose empty shards, unexpected selection, poor balance or cache behavior. |
| [Releases and upgrades](releases.md) | Configure protected publication credentials, release from a tested version tag and manage upgrades. |
| [PlantUML diagrams](diagrams/README.md) | Find editable diagram sources and regenerate the committed previews. |

## Boundaries

ShardPilot owns class assignment and candidate exclusion, and can optionally configure Gradle's
test-process heap, parallelism and replacement limits. The consumer owns test frameworks,
source sets, lifecycle wiring, framework threads, retries, containers and the total resource budget.
CI owns launching every shard and deciding whether the complete pipeline has passed.

Timing history is optional UTF-8 TSV. The plugin does not import JUnit XML, export updated timings,
fetch history remotely or update a versioned history file. A history producer is a separate concern.

The [compatibility matrix](testing.md#gradle-and-jdk-matrix) defines eight Linux CI targets for
Gradle 9.0.0, 9.1.0 and 9.8.1 with JDK 17, 21 and 25. Local verification also uses Windows.
Java 17 plugin bytecode alone is not a compatibility claim; support requires passing matrix
results for the release revision. See the [verification scope](testing.md#verified-scope).

## Project records

- [Example index](../examples/README.md): runnable scenarios grouped by framework.
- [Changelog](../CHANGELOG.md): released changes, upcoming work and limitations.
- [Contribution guidelines](../CONTRIBUTING.md): implementation and review expectations.
- [MIT license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md): distribution terms.

No service-specific application configuration or backend migration is required by these guides.
