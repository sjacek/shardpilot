# ShardPilot documentation

ShardPilot splits the classes of an opted-in Gradle `Test` task between independent executions.
Each execution calculates ownership locally from the same inputs. The Maven adapter applies that
same core to Surefire/Failsafe. There is no coordinator service or replacement test engine.

This checkout documents [0.3.0](releases/0.3.0.md), including class-manifest verification and
standalone JSON/HTML coverage reports and a Maven Surefire/Failsafe adapter over the shared core.
Version [0.2.1](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.2.1) provides
sharding and optional test-process controls. Use the
[root README](../README.md#getting-started) to apply it from the Gradle Plugin Portal, or the
[local development setup](../README.md#local-plugin-development) to test a checkout explicitly.
The [0.3.0 release notes](releases/0.3.0.md) contain migration examples and the publication checklist.

## Guides

| Guide | Read it when you need to… |
| --- | --- |
| [Configuration](configuration.md) | Opt in tasks, supply history, use Groovy/Kotlin DSL or configure multiple projects. |
| [Maven adapter](maven.md) | Configure `pom.xml`, native runner filters, suite namespaces and Maven lifecycle/CI artifact goals. |
| [Gradle/Maven configuration map](configuration.md#gradle-and-maven-configuration-map) | Translate count/index, eligibility, output paths and process controls between adapters. |
| [Architecture and assignment](architecture.md) | Understand components, class-family ownership, balancing and task inputs. |
| [CI integration](ci.md) | Run all shards with consistent inputs, isolated workspaces and separate reports. |
| [Class manifests and coverage](shard-verification.md) | Configure artifact gates, read HTML assignment statuses, expose a GitLab MR link and distinguish selection from test completion. |
| [PostgreSQL and RabbitMQ example](testcontainers-example.md) | Run a JUnit Jupiter integration suite with shared worker-local Testcontainers fixtures and isolated test data. |
| [Spock with PostgreSQL and RabbitMQ](spock-testcontainers-example.md) | Run specifications and data-table iterations with worker-local containers and specification-local databases. |
| [TestNG with PostgreSQL and RabbitMQ](testng-testcontainers-example.md) | Run class fixtures and data-provider rows with the native TestNG adapter and worker-local containers. |
| [Testing and compatibility](testing.md) | Verify changes and understand what the existing tests actually cover. |
| [Troubleshooting](troubleshooting.md) | Diagnose empty shards, unexpected selection, poor balance or cache behavior. |
| [Releases and upgrades](releases.md) | Configure protected publication credentials, release from a tested version tag and manage upgrades. |
| [Maven Central setup](maven-central.md) | Reuse the verified namespace, create a token/signing key and configure the Maven publishing environment. |
| [Publication descriptions](publication-descriptions.md) | Review the complete Maven Central and Gradle Plugin Portal descriptions and their build metadata sources. |
| [PlantUML diagrams](diagrams/README.md) | Find editable diagram sources and regenerate the committed previews. |

## Boundaries

ShardPilot owns class assignment and candidate exclusion, and can optionally configure Gradle's
test-process heap, parallelism and replacement limits. Opt-in manifest tasks prepare and verify
class-selection artifacts collected by CI and render their assignments in standalone HTML.
The consumer owns report publication and artifact access as well as test frameworks,
source sets, lifecycle wiring, framework threads, retries, containers and the total resource budget.
CI owns launching every shard and deciding whether the complete pipeline has passed.

Timing history is optional UTF-8 TSV. The plugin does not import JUnit XML, export updated timings,
fetch history remotely or update a versioned history file. A history producer is a separate concern.

The [compatibility matrix](testing.md#gradle-and-jdk-matrix) defines eight Linux CI targets for
Gradle 9.0.0, 9.1.0 and 9.8.1 with JDK 17, 21 and 25. Local verification also uses Windows.
Java 17 plugin bytecode alone is not a compatibility claim; support requires passing matrix
results for the release revision. See the [verification scope](testing.md#verified-scope).
The same workflow requires Maven 3.9.16 on JDK 17, 21 and 25. Gradle framework fixtures and
Configuration Cache checks do not establish Maven framework/version coverage; native Maven fixtures
currently exercise JUnit Jupiter with Surefire/Failsafe 3.5.4.

## Project records

- [Example index](../examples/README.md): runnable scenarios grouped by build tool and framework.
- [Source-module development](../CONTRIBUTING.md#source-layout-and-local-development): both build entry points, IDE import and distribution boundaries.
- [Changelog](../CHANGELOG.md): released changes, upcoming work and limitations.
- [Contribution guidelines](../CONTRIBUTING.md): implementation and review expectations.
- [MIT license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md): distribution terms.

No service-specific application configuration or backend migration is required by these guides.
