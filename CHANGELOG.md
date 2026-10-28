# Changelog

Notable changes to ShardPilot are recorded here. Release dates follow the version creation dates
recorded by the Gradle Plugin Portal.

## Unreleased

### Added

- Opt-in Gradle `history {}` configuration and `refresh<Task>ShardDurations` tasks create a
  candidate TSV from existing JUnit XML reports without rerunning tests or modifying versioned history.
- Shared refresh logic normalizes complete class families, excludes skipped/retried/ambiguous
  measurements and reports coverage plus before/after recorded shard loads in JSON, including data failures.

### Fixed

- Maven Central publication retries temporary status-check failures without uploading the
  bundle again. Maintainers can resume an accepted deployment by ID after a timeout;
  the workflow retains its release identity and last observed status for recovery.

## [0.3.1](docs/releases/0.3.1.md)

### Fixed

- Maven Central artifacts explicitly include the project homepage in each published POM, so
  artifact listings can find it without resolving the parent POM.

- Release checks reject missing or incorrect homepage URLs in the uploaded POMs, even when
  Maven's effective model inherits a valid URL from the parent.

Existing sharding configuration, YAML manifests and JSON/HTML reports are unchanged.
See the [0.3.1 release notes](docs/releases/0.3.1.md) for upgrade and verification steps.

## [0.3.0](docs/releases/0.3.0.md)

### Added

- **First Maven version of ShardPilot.** Split unit and integration test classes across CI jobs
  using native Surefire/Failsafe. Configure `io.github.sjacek:shardpilot-maven-plugin:0.3.0` in
  `pom.xml`; see the [Maven setup guide](docs/maven.md) and [runnable POMs](examples/maven/README.md).
- Maven Central distribution includes source and Javadoc JARs for IDE navigation, signed together
  with the parent, core and adapter. Maintainers have a [token and signing-key setup guide](docs/maven-central.md)
  and one workflow that publishes Gradle on a version tag, then Maven Central manually from the same tag.
- Optionally verify that every eligible class belongs to exactly one shard and each node's
  selected classes match the common plan. Fail the CI gate when selection evidence is missing
  or inconsistent, without rerunning tests. See [class-manifest verification](docs/shard-verification.md).
- Find which shard owns a class in the standalone `coverage.html` report. Compare planned and
  actual assignments and diagnose missing evidence, including after verification fails. Open
  the file locally or [expose it in a GitLab merge request](docs/shard-verification.md#finding-a-class-assignment-in-gitlab).
- Inspect and collect readable YAML manifests containing the full candidate list, planned
  partitions and each node's actual selection. Gradle and Maven use the same manifest format;
  JSON reports remain available for CI automation.

### Changed

- Runnable examples are now grouped by build tool: [Gradle examples](examples/gradle/README.md)
  cover JUnit Jupiter, Spock and TestNG with Testcontainers; [Maven examples](examples/maven/README.md)
  cover Surefire/Failsafe and parallel builds of multiple modules.

### Compatibility and migration

- Existing Gradle sharding and test-process configuration continues to work. Enable the new
  manifest verification explicitly when adding it to a pipeline.
- When upgrading from development snapshots with text manifests, regenerate the common plan
  and every node's evidence together in YAML; do not mix old and new artifacts.
- Coverage reports verify class selection. Keep every normal test job required, because matching
  selections do not prove that test methods passed.

See the [0.3.0 release notes](docs/releases/0.3.0.md) for setup and upgrade details.

## [0.2.1](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.2.1)

### Changed

- Expanded the Plugin Portal and Maven description to explain the purpose of parallel CI sharding,
  duration-based balancing, existing test-framework support and optional test-process controls.
  The README introduction now describes the same intended use.
- Supersedes the unpublished 0.2.0 candidate. Test-process configuration and sharding behavior
  are unchanged; the new version publishes the expanded description from a new immutable tag.
- See the [0.2.1 release notes](docs/releases/0.2.1.md) for publication and consumer verification.

## [0.2.0](docs/releases/0.2.0.md) — not published

### Added

- Optional per-task `testProcesses` configuration for Gradle's `maxHeapSize`, `maxParallelForks`
  and `forkEvery`, independent of sharding. Omitted settings stay untouched; explicit settings
  accept providers, validate at configuration time, and participate in test-task cache inputs.
- Real TestKit worker probes for heap limits, process reuse/replacement, concurrency,
  configuration-cache restoration and process-policy input changes, plus Kotlin DSL coverage.

### Changed

- Expanded the README to explain CI testing bottlenecks, parallel shards, duration-aware workload
  balancing and practical speedup limits. Project, Plugin Portal and Maven metadata share one summary.
- Installation, CI and release guides now use the published `0.1.0` plugin by default, with
  composite builds reserved for explicit local plugin development and checkout-based examples.

### Compatibility and migration

- Existing sharding configuration, assignment and native test-process settings remain unchanged
  unless the new `testProcesses` block explicitly configures a setting. Sharding can stay disabled
  while process settings are used. Direct Gradle `Test` setters remain supported.
- All eight Gradle/JDK target pairs passed locally on Linux amd64 on 23 October 2026: 67 tests per
  pair, 536 executions, no failures or skips. The tag workflow reruns the complete matrix for the
  exact release commit before publication.
- See the [0.2.0 release notes](docs/releases/0.2.0.md) for upgrade examples and publication checks.

## [0.1.0](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.1.0) - 2026-10-20

### Added

- Tag-triggered Plugin Portal publication gated by an offline version check and the complete
  reusable eight-cell Gradle/JDK matrix at the same commit SHA. Publication uses scoped environment
  secrets, mandatory Portal validation and no shared Gradle/configuration caches.
- A configuration-cache-compatible `verifyReleaseTag` task with strict stable-version matching,
  TestKit guard regressions and structural YAML tests for release dependencies and secret isolation.
- An eight-cell GitHub Actions compatibility matrix for Gradle 9.0.0, 9.1.0 and 9.8.1 with
  Temurin JDK 17, 21 and 25, excluding the unsupported Gradle 9.0.0/JDK 25 pair. Every cell runs
  all Docker-free checks with strict configuration-cache TestKit fixtures and retains reports.
- A `testGradleVersion` build property selecting the Gradle distribution for all functional
  consumers and a `testJavaVersion` expectation, with assertions for the actual outer worker,
  nested Gradle/JDK and consumer test-worker JDK.
- Gradle plugin `io.github.sjacek.shardpilot` for opt-in, class-level test sharding without a
  coordinator service or a replacement test runner.
- A per-task `shardPilot` extension on standard `Test` tasks, including lazily registered tasks.
  Settings include `enabled`, `shardCount`, `shardIndex` and an optional `durationsFile`.
- One-based shard selection through the `shardpilot.count` and `shardpilot.index` Gradle properties,
  with defaults of `1` and validation of invalid indices and counts.
- Deterministic longest-processing-time-first assignment of measured class families, with class-name
  and shard-index tie-breaking. Unmeasured classes use stable string hashing and `floorMod`.
- Nested-class grouping with the outer class and deterministic aggregation of nested timing records.
- UTF-8 TSV history parsing with comments and blank lines. Invalid names, duplicate class records,
  negative/non-finite durations and accumulated duration overflow are rejected.
- Hash-only assignment when timing history is unset or missing.
- Shard ownership filtering that intersects existing file includes and excludes without widening
  the original test suite. Runtime classes remain available for normal framework discovery.
- Explicit test task inputs for the enabled flag, shard count, index and history contents, keeping
  shard results distinct for Gradle up-to-date and build-cache checks.
- Serializable filtering and preparation actions without captured `Project` or `Test` instances,
  with strict configuration-cache functional verification.
- Metadata-only logging of shard index, count and recorded workload. Parser errors identify the
  offending line without echoing arbitrary history contents.
- Unit tests for parsing, validation, deterministic assignment, nested classes, hash fallback,
  floating-point aggregation and overflow handling.
- Gradle TestKit coverage for JUnit Jupiter, Groovy Spock and native TestNG tasks, nested tests, complete/non-overlapping
  weighted and hash-only shards, existing filters, custom tasks, command-line test selection,
  empty-shard report cleanup, failed test propagation, Groovy/Kotlin DSL and configuration-cache reuse.
- Gradle Wrapper pinned to version `9.8.1`, with distribution checksum verification.
- Plugin bytecode targeting Java 17, plugin validation, source/Javadoc artifacts and Maven
  publication metadata containing license information.
- MIT licensing for the original plugin, preserved Apache 2.0 licensing for the Gradle Wrapper,
  third-party notices and license files in distributable JARs.
- English usage documentation and contribution guidelines.
- Configuration, architecture, CI, testing, troubleshooting and release guides, with PlantUML
  diagram sources and locally rendered SVG previews.
- A standalone PostgreSQL/RabbitMQ Testcontainers example with worker-local parallel startup,
  class-local databases, isolated queues, illustrative shard weights and a lifecycle diagram.
- An analogous Spock/Groovy Testcontainers example with `setupSpec`, specification-local databases,
  a data-driven feature, complete-specification sharding and a separate lifecycle guide/diagram.
- An analogous TestNG Testcontainers example with inherited `@BeforeClass`, class-local databases,
  sequential data-provider invocations and a separate lifecycle guide/diagram.
- TestNG regression fixtures covering complete/non-overlapping weighted and hash-only shards,
  data-provider ownership, configuration-cache reuse, empty reports and fatal setup failures.

### Changed

- Runnable examples are grouped by framework and scenario under `examples/junit-jupiter/testcontainers`
  and `examples/spock/testcontainers`, with TestNG now available under `examples/testng/testcontainers`.

### Scope and limitations

- This is the initial Gradle Plugin Portal release.
- Initial local verification used Gradle `9.8.1` on Windows with JDK `25`. Broader support requires
  passing the eight-cell Linux Gradle/JDK matrix for the release revision; Java 17 bytecode alone
  is not a compatibility claim for untested combinations.
- Kotlin DSL configuration is exercised; Kotlin-authored tests, Android, Kotlin Multiplatform
  and native-image test execution are not yet verified.
- JUnit XML history export, remote history retrieval and automatic updates to versioned timing
  files are not included.
- CI remains responsible for launching all shards with identical plan inputs and isolated build
  directories. ShardPilot does not coordinate or verify completion across CI jobs.
- Gradle/framework discovery and no-matching-tests failure behavior are retained. In particular,
  an explicit `--tests` selection can fail on a shard that does not own the selected class.
- No source-set creation, framework dependency injection, automatic retry, container provisioning,
  method-level splitting or implicit change to test parallelism is introduced.
- Service-specific configuration and backend migrations are outside this plugin's initial scope.
