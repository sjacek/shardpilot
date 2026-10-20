# ShardPilot documentation

ShardPilot splits the classes of an opted-in Gradle `Test` task between independent executions.
Each execution calculates ownership locally from the same inputs. There is no coordinator service
or replacement test engine.

This documentation describes `0.1.0-SNAPSHOT`, an unpublished development version. Use the
[root README](../README.md#getting-started) for local composite-build installation. Do not assume
that a matching version is available from the Gradle Plugin Portal.

## Guides

| Guide | Read it when you need to… |
| --- | --- |
| [Configuration](configuration.md) | Opt in tasks, supply history, use Groovy/Kotlin DSL or configure multiple projects. |
| [Architecture and assignment](architecture.md) | Understand components, class-family ownership, balancing and task inputs. |
| [CI integration](ci.md) | Run all shards with consistent inputs, isolated workspaces and separate reports. |
| [PostgreSQL and RabbitMQ example](testcontainers-example.md) | Run an integration suite with shared worker-local Testcontainers fixtures and isolated test data. |
| [Testing and compatibility](testing.md) | Verify changes and understand what the existing tests actually cover. |
| [Troubleshooting](troubleshooting.md) | Diagnose empty shards, unexpected selection, poor balance or cache behavior. |
| [Releases and upgrades](releases.md) | Prepare a public release and manage plugin, Wrapper and dependency updates. |
| [PlantUML diagrams](diagrams/README.md) | Find editable diagram sources and regenerate the committed previews. |

## Boundaries of the initial version

ShardPilot owns class assignment and candidate exclusion. The consumer owns test frameworks,
source sets, lifecycle wiring, test parallelism, retries, containers and resource isolation.
CI owns launching every shard and deciding whether the complete pipeline has passed.

Timing history is optional UTF-8 TSV. The plugin does not import JUnit XML, export updated timings,
fetch history remotely or update a versioned history file. A history producer is a separate concern.

The current verification target is Gradle 9.8.1 on Windows with JDK 25. Java 17 plugin bytecode is
not a claim that every Java 17/Gradle combination is supported. See the
[verification scope](testing.md#verified-scope) before choosing a different environment.

## Project records

- [Changelog](../CHANGELOG.md): unreleased features and limitations.
- [Contribution guidelines](../CONTRIBUTING.md): implementation and review expectations.
- [MIT license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md): distribution terms.

No service-specific application configuration or backend migration is required by these guides.
