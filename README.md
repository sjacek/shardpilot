# ShardPilot

Duration-balanced, class-level test sharding for Gradle. No coordinator, CI vendor lock-in,
test-runner replacement, telemetry, or runtime dependencies outside the Gradle API.

Plugin ID: `io.github.sjacek.shardpilot` · License: [MIT](LICENSE).

The Gradle Wrapper retains its upstream Apache 2.0 license; see [third-party notices](THIRD_PARTY_NOTICES.md).

[Getting started](#getting-started) · [Configuration](#configuration) ·
[Timing history](#timing-history-and-assignment) · [Examples](examples/README.md) · [Documentation](docs/README.md) · [Changelog](CHANGELOG.md) ·
[Contributing](CONTRIBUTING.md)

## Why ShardPilot?

A large test task can dominate a CI pipeline even when the project has only one module.
ShardPilot distributes its **test classes**, rather than whole modules, between independent jobs.
Recorded durations improve balance; a deterministic hash assigns classes without measurements.

- Opt-in configuration on each standard Gradle `Test` task.
- Longest-processing-time-first balancing with deterministic tie-breaking.
- Nested classes remain with their outer class, preserving class-level test setup.
- Existing file includes, excludes and framework discovery remain in effect.
- No additional service, test engine, automatic retries or CI-specific environment detection.

## Status

Initial development version (`0.1.0-SNAPSHOT`), not published to the Gradle Plugin Portal.
The initial verification target is Gradle 9.8.1 on Windows with JDK 25. Plugin bytecode targets
Java 17; that alone is not a claim of compatibility with every Gradle/JDK combination.
Android, Kotlin Multiplatform and native-image test execution are outside this first version.

ShardPilot works on standard Gradle `Test` tasks. It does not create source sets, choose a test
framework, add test dependencies, start containers, configure retries, or change failure policy.
Java JUnit Jupiter, nested Jupiter tests, Groovy Spock tests and native TestNG tasks are covered by
functional fixtures, including TestNG data-provider invocations.
Kotlin DSL build configuration has a separate fixture; Kotlin-authored test classes are not yet verified.

## Getting started

This development version is consumed from a local checkout, not from an assumed Plugin Portal
release. The consumer needs an existing JVM project and a configured test framework. ShardPilot
does not replace the project's Gradle Wrapper or select its JDK.

### 1. Include the plugin build

In the consumer's `settings.gradle`:

```groovy
pluginManagement {
    includeBuild('../shardpilot') // Path to your local checkout, not a required repository layout.
}
```

Or, in `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../shardpilot")
}
```

Replace the path with the location of the ShardPilot checkout. Keep any existing plugin
repositories and other settings; add the included build to the existing `pluginManagement` block.

### 2. Opt in a test task

In `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'io.github.sjacek.shardpilot'
}

tasks.named('test', Test) {
    useJUnitPlatform() // Your existing framework configuration remains yours.
    shardPilot {
        enabled = true
        durationsFile = layout.projectDirectory.file('test-durations.tsv')
    }
}
```

The same extension is available on custom `integrationTest` tasks, including tasks registered
after applying the plugin. Each task opts in separately; other tasks keep their original test set.
Apply the plugin in each participating subproject instead of configuring sibling projects implicitly.

### 3. Run the shards

Run each command in a separate CI job or isolated checkout, using one-based indices:

```shell
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=2
```

Both properties default to `1`, so ordinary local runs execute the full suite. DSL properties
`shardCount` and `shardIndex` can override the CLI defaults. Invalid settings fail rather than
silently selecting a different shard. CI is responsible for launching every index exactly once.

Both commands target the **same task**, but execute different class subsets. They must not run
concurrently against the same build directory because Gradle test outputs would share paths.

### Kotlin DSL task configuration

In `build.gradle.kts`, after including the plugin build in settings:

```kotlin
import io.github.sjacek.shardpilot.ShardPilotExtension

plugins {
    java
    id("io.github.sjacek.shardpilot")
}

tasks.test {
    useJUnitPlatform()
    extensions.configure<ShardPilotExtension>("shardPilot") {
        enabled.set(true)
        durationsFile.set(layout.projectDirectory.file("test-durations.tsv"))
    }
}
```

## Configuration

The `shardPilot` extension belongs to each `Test` task, not to the root project.

| Setting | Default | Purpose |
| --- | --- | --- |
| `enabled` | `false` | Explicitly opts this task into sharding. |
| `shardCount` | `-Pshardpilot.count`, otherwise `1` | Total number of shards. Must be positive. |
| `shardIndex` | `-Pshardpilot.index`, otherwise `1` | One-based index, between `1` and `shardCount`. |
| `durationsFile` | Unset | Optional UTF-8 timing history; absent history uses hash-only assignment. |

For an **already registered** integration test task, configure it independently:

```groovy
tasks.named('integrationTest', Test) {
    shardPilot {
        enabled = true
        durationsFile = layout.projectDirectory.file('integration-test-durations.tsv')
    }
}
```

Then use `./gradlew integrationTest -Pshardpilot.count=2 -Pshardpilot.index=1` and the equivalent
command for index `2`. Applying ShardPilot does not register an `integrationTest` task or add it
to `check`; those lifecycle decisions remain with the consumer.

To pin a task's settings in the DSL instead of using CLI defaults:

```groovy
tasks.named('test', Test) {
    shardPilot {
        enabled = true
        shardCount = 2
        shardIndex = 1
    }
}
```

Explicit DSL assignments take precedence over the CLI defaults. Do not hard-code an index in a
shared build when different CI jobs need to select different indices.

## Timing history and assignment

The UTF-8 file contains a binary class name, a **tab**, and seconds, one record per line:

```text
# Each gap below is a tab, not spaces.
example.SlowIntegrationTest	120.5
example.FastIntegrationTest	2.0
example.SlowIntegrationTest$Nested	8.5
```

Use one history file per independently planned test task. Missing or unset history is a supported
cold start. Malformed records, negative/non-finite durations and duplicate class records fail with
a line number; errors do not echo arbitrary file contents into CI logs.

Measured classes are sorted by descending duration, then ascending name. Each is assigned to the
least-loaded shard, with ties resolved by the lowest index. Unmeasured classes use Java's stable
string hash and `floorMod`. Nested classes are grouped with their outer class; their measured
durations are added together. Records for removed tests do not resurrect those tests, but may
distort balance until the history is refreshed.

All jobs **must use the same revision, history, shard count and test selection**. Do not rewrite
the history while shards are running. ShardPilot does not fetch data remotely or automatically
modify versioned history. Automatic JUnit XML history export is not part of this initial version.

Sharding intersects the task's original file includes/excludes; it never widens the suite.
Framework discovery and `--tests` still belong to Gradle. An explicit selection can match nothing
on a non-owning shard, and Gradle's usual no-matching-tests failure is intentionally not suppressed.
For focused local runs, omit sharding CLI arguments or set both count and index to `1`.

Only classes are distributed. Methods stay together, and framework/Gradle parallelism is not
enabled or disabled. Startup, container reuse, Spring context locality and unknown classes can
affect wall-clock balance; recorded seconds are not a promise of a particular speedup.

## Cache and safety

The enabled flag, shard count, index and history contents are explicit test task inputs, so cached
results from one shard cannot substitute for another. The filter and action capture no `Project`
or `Test` instance. Configuration-cache verification runs in strict failure mode.

ShardPilot does not make external-resource tests safe to cache. Consumers still own the
up-to-date/build-cache policy for tests that depend on databases, networks or other mutable state.
Test output is not parsed or uploaded, and the plugin never retries or forgives a test failure.

## Build and verify

```shell
./gradlew check assemble
```

On Windows, use `gradlew.bat`. Functional tests use Gradle TestKit and temporary projects with
JUnit/Spock/TestNG dependencies from Maven Central; the first run requires network access. Keep TestKit
execution sequential because it starts additional Gradle daemons and test workers.

PowerShell:

```powershell
.\gradlew.bat check assemble
```

Verification covers the planner and parser, weighted and hash-only shards, JUnit Jupiter and
Spock and native TestNG execution, data-provider rows, nested classes, pre-existing filters,
custom tasks, empty-shard report cleanup,
failure propagation, Groovy/Kotlin DSL and configuration-cache reuse. Sources and Javadoc JARs
are built alongside the plugin JAR; each contains the MIT license.

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution requirements and
[CHANGELOG.md](CHANGELOG.md) for the unreleased feature history.

## Detailed documentation

The [documentation index](docs/README.md) links to configuration, architecture, CI integration,
testing, troubleshooting and release guides. Architecture and CI guides include rendered diagrams
with editable [PlantUML sources](docs/diagrams/README.md).

The runnable [JUnit Jupiter PostgreSQL and RabbitMQ example](examples/junit-jupiter/testcontainers/README.md) demonstrates
worker-local Testcontainers startup, isolated data and a two-shard integration suite. Its
[detailed guide](docs/testcontainers-example.md) explains lifecycle and CI resource boundaries.
The [Spock counterpart](examples/spock/testcontainers/README.md) adds specification-local database URLs
and data-table iterations; see its [guide](docs/spock-testcontainers-example.md).
The [TestNG counterpart](examples/testng/testcontainers/README.md) adds inherited class fixtures
and data-provider invocations; see its [guide](docs/testng-testcontainers-example.md).
The [example index](examples/README.md) groups all three scenarios by framework.

## Before a public release

- Verify the public repository location, plugin namespace ownership and copyright attribution.
- Add Plugin Portal publication metadata and credentials through the host's protected secret store.
- Extend the tested Gradle/JDK/OS matrix before advertising broader compatibility.
- Publish an immutable release version, not this development snapshot.
- Keep service-specific exclusions, CI topology, test data and infrastructure out of this repository.

## License

ShardPilot's original implementation is distributed under the [MIT license](LICENSE).
Third-party components retain their own licenses; the generated Gradle Wrapper is covered
separately in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
