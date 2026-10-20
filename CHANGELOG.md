# Changelog

Notable changes to ShardPilot are recorded here. Changes under **Unreleased** are not a published
release. The current build version is `0.1.0-SNAPSHOT`; no public release is recorded yet.

## Unreleased

### Added

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
- Gradle TestKit coverage for JUnit Jupiter and Groovy Spock, nested tests, complete/non-overlapping
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

### Scope and limitations

- This is the initial development version, not a Gradle Plugin Portal release.
- Initial verification targets Gradle `9.8.1` on Windows with JDK `25`. Java 17 bytecode is not a
  compatibility claim for untested Gradle/JDK combinations.
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
