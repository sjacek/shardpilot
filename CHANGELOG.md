# Changelog

ShardPilot splits test suites across parallel Gradle or Maven jobs to shorten CI runs.
Changes under **Unreleased** require the development version and are not yet published.
Release dates follow the version creation dates recorded by the Gradle Plugin Portal.

For build, CI and publication details, see the [maintainer documentation](docs/README.md).

## Unreleased

### Added

- Automatically include changed tests and tests affected by module or JSON/YAML contract changes.
  Use the same configuration in Gradle and Maven. [Setup guide](docs/selection-impact.md).
- Diagnose test timeouts, interrupted runs and memory use. Detect leftover containers without
  removing them. [Setup guide](docs/execution-diagnostics.md).
- Check compiled files shared between test jobs before running tests. Missing, changed or wrong-version
  inputs stop execution. [Setup guide](docs/compiled-artifacts.md).
- Check test results for missing reports, failed processes and unexpected skips. Get a readable report
  explaining any problems. [Setup guide](docs/execution-evidence-contract.md).
- Choose all tests, a quick check or a custom group in Gradle or Maven. Set size or estimated time
  limits while keeping required tests. [Setup guide](docs/selection-policy.md).
- Select tests using your rules for files changed in Git. If change information is unavailable,
  configure a full test run or stop with an error.
- Share one test selection across parallel jobs. Stop if their source versions or test settings differ.

### Fixed

- Memory reports keep valid measurements when an optional OOM counter is unreadable.
- Failed report generation no longer leaves an old JSON success report behind.
- Repeating the Maven quick-test example no longer counts results from earlier runs.

## [0.5.0](docs/releases/0.5.0.md) — 2026-11-13

### Added

- Run a chosen group of tests in Gradle or Maven, optionally split across parallel jobs.
  [Setup guide](docs/test-selection.md).
- Keep nested tests together. See which job is assigned each chosen test in a separate report.

### Security

- Added [instructions for reporting vulnerabilities privately](SECURITY.md) and a
  [guide to plugin permissions and trust boundaries](docs/security.md).
- Inspect [dependency lists and vulnerability reports](docs/dependency-security.md) for Gradle and Maven.
- Release builds [check dependency downloads](docs/dependency-verification.md) and reject files
  with missing or unexpected checksums, including cached files.

### Upgrade

- Upgrade the Gradle plugin or Maven adapter to `0.5.0`. Choosing a test subset is optional;
  existing configurations keep running their configured test suite.
- Give parallel jobs the same selection file, timing history, plugin version and job count.
  Require both test jobs and selection checks to pass. [Upgrade guide](docs/releases/0.5.0.md).

## [0.4.0](docs/releases/0.4.0.md) — 2026-11-05

### Added

- Refresh duration history from existing test reports without rerunning tests. Gradle provides
  opt-in `history {}` configuration and `refresh<Task>ShardDurations` tasks; Maven provides
  `refresh-durations` for Surefire and Failsafe reports.
- Review a candidate TSV history and a JSON report showing timing coverage and the effect on
  recorded shard loads before adopting new weights. Skipped, retried and ambiguous measurements
  are excluded to help avoid misleading history updates.
- Follow runnable [history-refresh examples and CI collection flows](docs/history-refresh.md)
  for collecting reports, diagnosing missing inputs and adopting updated history.

### Fixed

- Identical timing inputs now produce identical refresh reports across JVMs, making history
  comparisons reliable when recorded shard loads contain fractional durations.

### Upgrade

- Enable Gradle `history {}` or invoke Maven `refresh-durations` to use duration refresh.
  Review the generated candidate and give every shard the same adopted history in the next
  pipeline. Refresh does not replace your maintained history automatically.
- See the [0.4.0 upgrade guide](docs/releases/0.4.0.md#upgrade-and-opt-in) for Gradle and Maven examples.

## [0.3.1](docs/releases/0.3.1.md) — 2026-10-24

### Fixed

- Maven artifact listings and tools can find the project homepage directly from each published
  POM, without resolving the parent POM.

See the [0.3.1 release notes](docs/releases/0.3.1.md) for upgrade details.

## [0.3.0](docs/releases/0.3.0.md) — 2026-10-23

### Added

- **Maven support:** split unit and integration test classes across CI jobs using native
  Surefire and Failsafe. Configure `io.github.sjacek:shardpilot-maven-plugin:0.3.0` in `pom.xml`;
  see the [Maven setup guide](docs/maven.md) and [runnable examples](examples/maven/README.md).
- Maven source and Javadoc JARs support IDE navigation and API documentation.
- Optionally verify that every eligible class belongs to exactly one shard and that each node
  selected the classes assigned to it. Missing or inconsistent evidence fails verification;
  see [class-manifest verification](docs/shard-verification.md).
- Find a test class's shard in the standalone `coverage.html` report. Compare planned and actual
  assignments and diagnose missing evidence, including after verification fails. You can also
  [link the report from a GitLab merge request](docs/shard-verification.md#finding-a-class-assignment-in-gitlab).
- Read common plans and actual selections as YAML manifests, using the same format in Gradle
  and Maven. JSON reports remain available for CI automation.

### Upgrade

- Enable manifest verification explicitly when adding it to an existing pipeline. Keep normal
  test jobs required as well: matching class selections do not prove that test methods passed.
- When upgrading from development snapshots with text manifests, regenerate the common plan
  and every node's evidence together in YAML. Mixing old and new artifacts is unsupported.
- Runnable examples moved into [Gradle](examples/gradle/README.md) and
  [Maven](examples/maven/README.md) groups. Update local paths or CI commands that use the old locations.

See the [0.3.0 release notes](docs/releases/0.3.0.md) for setup and migration examples.

## [0.2.1](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.2.1) — 2026-10-23

### Changed

- Expanded the project and Plugin Portal descriptions to explain when sharding helps CI,
  how duration-based balancing works and which test frameworks are supported.
- Published the features prepared for the unpublished 0.2.0 candidate. Use 0.2.1 when following
  its test-process configuration examples; see the [release notes](docs/releases/0.2.1.md).

## [0.2.0](docs/releases/0.2.0.md) — not published

### Added

- Configure Gradle test-process memory, parallel forks and process replacement per task through
  the optional `testProcesses` block (`maxHeapSize`, `maxParallelForks` and `forkEvery`).
- Use test-process controls independently of sharding, including with sharding disabled.
  Settings accept Gradle providers and invalid values fail during configuration.

### Upgrade

- Configure only the process settings you want ShardPilot to manage; omitted settings retain
  their existing values. Direct Gradle `Test` configuration remains supported.
- This candidate was not published. Use 0.2.1 and the
  [configuration examples](docs/releases/0.2.0.md) to adopt these features.

## [0.1.0](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.1.0) — 2026-10-20

### Added

- Initial Gradle plugin `io.github.sjacek.shardpilot` for splitting test classes across independent
  CI jobs. Each job calculates its own assignment from the same inputs, without a coordinator service.
- Opt in standard or custom Gradle `Test` tasks through `shardPilot`. Configure `enabled`,
  `shardCount`, `shardIndex` and optional `durationsFile`; use `shardpilot.count` and
  `shardpilot.index` Gradle properties to select a one-based shard in CI.
- Balance shards using recorded class durations from a UTF-8 TSV file. Without history,
  stable hash-based assignment still gives each class family a consistent owner.
- Keep nested tests with their outer class and preserve existing task includes and excludes.
  Invalid shard settings and malformed timing history fail with actionable errors.
- Use JUnit Jupiter, Spock or TestNG with their native Gradle runners, Groovy or Kotlin DSL
  configuration, and Gradle Configuration Cache.
- Try runnable PostgreSQL/RabbitMQ Testcontainers examples for JUnit Jupiter, Spock and TestNG,
  with isolated test data and guidance for container lifecycle and CI resources. See the
  [example index](examples/README.md) for their current locations.
- Source and Javadoc JARs, MIT licensing, and [configuration, CI and troubleshooting guides](docs/README.md).

### Requirements and limitations

- Requires Java 17 or newer. Consult the [compatibility guide](docs/testing.md#gradle-and-jdk-matrix)
  for tested Gradle/JDK combinations.
- CI must launch every shard with identical planning inputs and separate build directories.
  This initial release does not verify completion across CI jobs.
- Tests are assigned by class family; individual methods and data-provider rows are not split
  across shards. Keep framework retries, test parallelism and container provisioning in your build.
- An explicit Gradle `--tests` request can fail on a shard that does not own the requested class.
- Kotlin-authored tests, Android, Kotlin Multiplatform and native-image execution were not verified
  for this release. Refreshing duration history from reports was introduced later in 0.4.0.
