# ShardPilot documentation

[![ShardPilot avatar: three parallel green shard towers and a mint fan-out arrow.](assets/branding/shardpilot-avatar-256.png)](assets/branding/README.md)

ShardPilot splits the classes of an opted-in Gradle `Test` task between independent executions.
Each execution calculates ownership locally from the same inputs. The Maven adapter applies that
same core to Surefire/Failsafe. There is no coordinator service or replacement test engine.

[0.5.0](releases/0.5.0.md) is published in Gradle Plugin Portal and Maven Central,
including explicit selection of a test subset for either adapter. Current snippets target this
release; use the [local development setup](https://github.com/sjacek/shardpilot/blob/main/README.md#local-plugin-development) for source changes.

The default [published/audited baseline is 0.5.0](published-artifact-audit.md#published-050-baseline-13-november-2026).
[Current audit status](security-review-2026-11-12.md#current-status-13-november-2026)
distinguishes registry evidence, source CI checks and remaining assurance work.

## Guides

| Guide | Read it when you need to… |
| --- | --- |
| <img src="assets/branding/guide-icons/configuration.svg" width="32" height="32" alt=""> [Configuration](configuration.md) | Opt in tasks, supply history, use Groovy/Kotlin DSL or configure multiple projects. |
| <img src="assets/branding/guide-icons/maven-adapter.svg" width="32" height="32" alt=""> [Maven adapter](maven.md) | Configure `pom.xml`, native runner filters, suite namespaces and Maven lifecycle/CI artifact goals. |
| <img src="assets/branding/guide-icons/configuration-map.svg" width="32" height="32" alt=""> [Gradle/Maven configuration map](configuration.md#gradle-and-maven-configuration-map) | Translate count/index, eligibility, output paths and process controls between adapters. |
| <img src="assets/branding/guide-icons/architecture.svg" width="32" height="32" alt=""> [Architecture and assignment](architecture.md) | Understand components, class-family ownership, balancing and task inputs. |
| <img src="assets/branding/guide-icons/history-refresh.svg" width="32" height="32" alt=""> [History refresh](history-refresh.md) | Generate candidate TSV/JSON, choose pruning, interpret coverage and review timing policies. |
| <img src="assets/branding/guide-icons/history-recovery.svg" width="32" height="32" alt=""> [History recovery](history-refresh.md#recover-a-missing-shard-download) | Diagnose missing shard XML, distinguish the three outcome gates and restore the original report cohort. |
| <img src="assets/branding/guide-icons/ci-integration.svg" width="32" height="32" alt=""> [CI integration](ci.md) | Run all shards with consistent inputs, isolated workspaces and separate reports. |
| <img src="assets/branding/guide-icons/coverage.svg" width="32" height="32" alt=""> [Class manifests and coverage](shard-verification.md) | Configure artifact gates, read HTML assignment statuses, expose a GitLab MR link and distinguish selection from test completion. |
| <img src="assets/branding/guide-icons/test-selection.svg" width="32" height="32" alt=""> [Explicit test selection](test-selection.md) | Validate a consumer-owned subset, expand eligible families and run one/many shards with separate reports. |
| <img src="assets/branding/guide-icons/junit-containers.svg" width="32" height="32" alt=""> [PostgreSQL and RabbitMQ example](testcontainers-example.md) | Run a JUnit Jupiter integration suite with shared worker-local Testcontainers fixtures and isolated test data. |
| <img src="assets/branding/guide-icons/spock-containers.svg" width="32" height="32" alt=""> [Spock with PostgreSQL and RabbitMQ](spock-testcontainers-example.md) | Run specifications and data-table iterations with worker-local containers and specification-local databases. |
| <img src="assets/branding/guide-icons/testng-containers.svg" width="32" height="32" alt=""> [TestNG with PostgreSQL and RabbitMQ](testng-testcontainers-example.md) | Run class fixtures and data-provider rows with the native TestNG adapter and worker-local containers. |
| <img src="assets/branding/guide-icons/compatibility.svg" width="32" height="32" alt=""> [Testing and compatibility](testing.md) | Verify changes and understand what the existing tests actually cover. |
| <img src="assets/branding/guide-icons/execution-equivalence.svg" width="32" height="32" alt=""> [Native execution equivalence](execution-equivalence.md) | Compare native test identities, invocation counts, outcomes and skips against two/four shards for the deterministic Gradle/Maven fixtures. |
| <img src="assets/branding/guide-icons/security.svg" width="32" height="32" alt=""> [Security and trust boundaries](security.md) | Understand private reporting, build-process permissions, metadata handling and publication responsibilities. |
| <img src="assets/branding/guide-icons/dependency-security.svg" width="32" height="32" alt=""> [SBOMs and dependency vulnerability scanning](dependency-security.md) | Separate Gradle/Maven runtime, build, test and provided inventories, candidate identities, native evidence and the CI severity/database gate. |
| <img src="assets/branding/guide-icons/dependency-remediation.svg" width="32" height="32" alt=""> [Producer dependency remediation](dependency-remediation-2026-11-13.md) | Scoped updates, verified green CI, the blocking Maven tooling boundary and retained Site/Jetty diagnostics. |
| <img src="assets/branding/guide-icons/dependency-integrity.svg" width="32" height="32" alt=""> [Build dependency integrity](dependency-verification.md) | Review immutable Actions pins, Gradle/TestKit and Maven checksum enforcement, cache protection and baseline updates. |
| <img src="assets/branding/guide-icons/security-review.svg" width="32" height="32" alt=""> [12 November 2026 review follow-up](security-review-2026-11-12.md) | Find the exact public reference commit, existing evidence, verification limits and remaining security/performance work. |
| <img src="assets/branding/guide-icons/artifact-audit.svg" width="32" height="32" alt=""> [Published artifact audit](published-artifact-audit.md) | Verify registry bytes and signatures, then observe warmed consumers in isolated Linux containers. |
| <img src="assets/branding/guide-icons/troubleshooting.svg" width="32" height="32" alt=""> [Troubleshooting](troubleshooting.md) | Diagnose empty shards, unexpected selection, poor balance or cache behavior. |
| <img src="assets/branding/guide-icons/releases.svg" width="32" height="32" alt=""> [Releases and upgrades](releases.md) | Configure protected publication credentials, release from a tested version tag and manage upgrades. |
| <img src="assets/branding/guide-icons/maven-central.svg" width="32" height="32" alt=""> [Maven Central setup](maven-central.md) | Reuse the verified namespace, create a token/signing key and configure the Maven publishing environment. |
| <img src="assets/branding/guide-icons/publication-descriptions.svg" width="32" height="32" alt=""> [Publication descriptions](publication-descriptions.md) | Review the complete Maven Central and Gradle Plugin Portal descriptions and their build metadata sources. |
| <img src="assets/branding/guide-icons/diagrams.svg" width="32" height="32" alt=""> [PlantUML diagrams](diagrams/README.md) | Find editable diagram sources and regenerate the committed previews. |

## Boundaries

ShardPilot owns class assignment and candidate exclusion, and can optionally configure Gradle's
test-process heap, parallelism and replacement limits. Opt-in manifest tasks prepare and verify
class-selection artifacts collected by CI and render their assignments in standalone HTML.
The consumer owns report publication and artifact access as well as test frameworks,
source sets, lifecycle wiring, framework threads, retries, containers and the total resource budget.
CI owns launching every shard and deciding whether the complete pipeline has passed.

Timing history is optional UTF-8 TSV. Opt-in refresh reads existing JUnit XML and writes a
candidate TSV plus JSON evidence. It never runs producers, fetches history remotely or adopts a
candidate into versioned history. CI collects one cohort; the consumer reviews and commits it.

The [compatibility matrix](testing.md#gradle-and-jdk-matrix) defines eight Linux CI targets for
Gradle 9.0.0, 9.1.0 and 9.9.1 with JDK 17, 21 and 25. Local verification also uses Windows.
The repository Wrapper pins Gradle 9.9.1; the CI matrix selects TestKit consumers independently.
Java 17 plugin bytecode alone is not a compatibility claim; support requires passing matrix
results for the release revision. See the [verification scope](testing.md#verified-scope).
The same workflow requires Maven 3.10.0 on JDK 17, 21 and 25. Gradle framework fixtures and
Configuration Cache checks do not establish Maven framework/version coverage; native Maven fixtures
currently exercise JUnit Jupiter with Surefire/Failsafe 3.5.4.

## Project records

- [Brand assets](assets/branding/README.md): the Grinnotech Platform-style avatar, scalable icon, PNG exports and agent orchestration artwork.
- [Example index](https://github.com/sjacek/shardpilot/blob/main/examples/README.md): runnable scenarios grouped by build tool and framework.
- [Source-module development](https://github.com/sjacek/shardpilot/blob/main/CONTRIBUTING.md#source-layout-and-local-development): both build entry points, IDE import and distribution boundaries.
- [Changelog](https://github.com/sjacek/shardpilot/blob/main/CHANGELOG.md): released changes, upcoming work and limitations.
- [Contribution guidelines](https://github.com/sjacek/shardpilot/blob/main/CONTRIBUTING.md): implementation and review expectations.
- [Security policy](https://github.com/sjacek/shardpilot/blob/main/SECURITY.md): private vulnerability reports and security-maintenance scope.
- [Coding standards](CODING_STANDARDS.md): declaration layout, CI example validation and inspection suppressions.
- [MIT license](https://github.com/sjacek/shardpilot/blob/main/LICENSE) and [third-party notices](https://github.com/sjacek/shardpilot/blob/main/THIRD_PARTY_NOTICES.md): distribution terms.

No service-specific application configuration or backend migration is required by these guides.
