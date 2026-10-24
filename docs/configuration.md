# Configuration

[Documentation index](README.md)

This page describes the Gradle DSL. Maven consumers configure native runners in `pom.xml` using
the [Maven guide](maven.md); the following map identifies shared and adapter-specific settings.

## Gradle and Maven configuration map

| Concern | Gradle adapter | Maven adapter |
| --- | --- | --- |
| Opt-in unit | Existing `Test` task with `shardPilot.enabled = true`. | Bound `select` execution for Surefire or Failsafe, with native `excludesFile` wiring. |
| Count/index CLI | `-Pshardpilot.count=N`, `-Pshardpilot.index=I`. | `-Dshardpilot.count=N`, `-Dshardpilot.index=I`. |
| Timing history | `shardPilot.durationsFile`. | Plugin `durationsFile` / `shardpilot.durationsFile`. Both read the same TSV. |
| Unsharded eligibility | Explicit `verification.eligibleClasses` matching the task's application restrictions. | Compiled classes matched by effective native includes/excludes and `additionalExcludesFile`. |
| Prepare common plan | `prepare<Task>ShardManifests`; source-set dependencies compile candidates. | `test-compile shardpilot:prepare`; compilation is explicitly requested. |
| Pre-worker evidence | `write<Task>ActualShardManifest`, attached to the owning task. | Bound `shardpilot:select`, generating native exclusions and `actual-N.yaml`. |
| Aggregate only | `verify<Task>Shards`. | `shardpilot:verify`. Both write JSON/HTML without scheduling producers. |
| Default namespace | `build/shardpilot/<task-name>/`. | `<module>/target/shardpilot/unit/` or `integration/`; custom `suite` supported. |
| JVM policy | Optional `testProcesses`; native Gradle settings remain available. | Native Surefire/Failsafe configuration; no `testProcesses` or equivalent `forkEvery` option. |
| Focused run | Native `--tests`; a non-owning shard can fail with no match. | `-Dtest`/`-Dit.test` rejected while selection/preparation runs; use an unsharded configuration. |

The ownership algorithm, nested-class families, YAML schema and report semantics are shared.
DSL opt-in, lifecycle phases and build-cache behavior are adapter-specific. A Gradle configuration
cannot be pasted into a POM; use the [runnable POMs](../examples/maven/README.md) for native wiring.
All jobs in one artifact cohort must use the same adapter/version, project/suite and selection inputs.

## Apply the Gradle adapter

First apply the published plugin as described in [Getting started](../README.md#getting-started).
An included build is only needed for [local plugin development](../README.md#local-plugin-development).
The plugin ID is `io.github.sjacek.shardpilot`. Applying it adds a `shardPilot` extension to every
standard Gradle `Test` task, including tasks registered later. It does not apply a JVM plugin or
create a test task.

## Per-task settings

| Property | Gradle type | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | `Property<Boolean>` | `false` | Opt this task into ownership filtering. |
| `shardCount` | `Property<Integer>` | `shardpilot.count` project property, otherwise `1` | Positive number of shards. |
| `shardIndex` | `Property<Integer>` | `shardpilot.index` project property, otherwise `1` | One-based index in `1..shardCount`. |
| `durationsFile` | `RegularFileProperty` | Unset | Optional UTF-8 TSV timing history for this task. |

These settings belong to the task, not a project-level extension. There is no `shardpilot.enabled`
CLI switch. Project properties provide conventions for count and index; explicit DSL assignments
override those conventions. Leave both values unpinned in a build that uses a CI shard matrix.

Even disabled tasks receive declared ShardPilot inputs. Their candidate selection is unchanged,
but changing those inputs can still affect Gradle's up-to-date/cache checks.

## Optional test processes (0.2.1 and later)

An explicit `shardPilot.testProcesses { ... }` block sets `maxHeapSize`, `maxParallelForks`
and/or `forkEvery` on the owning Gradle `Test` task. See the
[Groovy and Kotlin examples](../README.md#optional-test-process-settings).
Version 0.1.0 does not provide this API; it is available in
[0.2.1](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.2.1) and retained by 0.3.1.

| Property | Gradle type | Meaning |
| --- | --- | --- |
| `maxHeapSize` | `Property<String>` | Positive per-JVM size, in bytes or with a `k`/`m`/`g` suffix. |
| `maxParallelForks` | `Property<Integer>` | Positive maximum number of concurrent test processes in the task. |
| `forkEvery` | `Property<Long>` | Nonnegative classes per process; `0L` disables periodic replacement. |

There are no plugin defaults for these properties. Omitting the block or an individual property
preserves native task configuration. The block also works with sharding disabled. Providers are
resolved after the configuration action returns, before task inputs are snapshotted; later native
task assignments can override the values. All supplied values are validated before modifying the task.
Configured options become explicit task inputs so changing the process policy invalidates old results.
There is no automatic memory detection, memory-driven scheduling, retry or failure-policy change.

## Groovy DSL

```groovy
plugins {
    id 'java'
    id 'io.github.sjacek.shardpilot' version '0.3.1'
}

tasks.named('test', Test) {
    useJUnitPlatform() // Configure and supply your framework dependencies separately.
    shardPilot {
        enabled = true
        durationsFile = layout.projectDirectory.file('test-durations.tsv')
    }
}
```

For an **already registered** integration test task, opt it in independently:

```groovy
tasks.named('integrationTest', Test) {
    shardPilot {
        enabled = true
        durationsFile = layout.projectDirectory.file('integration-test-durations.tsv')
    }
}
```

This configuration does not register `integrationTest`, create its source set or attach it to
`check`. Those choices remain in the consumer's build.

## Kotlin DSL

```kotlin
import io.github.sjacek.shardpilot.ShardPilotExtension

plugins {
    java
    id("io.github.sjacek.shardpilot") version "0.3.1"
}

tasks.test {
    useJUnitPlatform()
    extensions.configure<ShardPilotExtension>("shardPilot") {
        enabled.set(true)
        durationsFile.set(layout.projectDirectory.file("test-durations.tsv"))
    }
}
```

Using the typed task extension avoids relying on a project-level accessor that does not exist.

## Run and select a shard

For a task enabled in the DSL, run the following commands in separate workspaces/jobs:

```shell
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=2
```

Use `gradlew.bat` on Windows. Invalid count/index settings are rejected when the enabled task's
plan is evaluated; non-integer CLI values fail provider conversion. Do not depend on validation
happening during initial project configuration.

For local focused execution, restore a single shard:

```shell
./gradlew test --tests 'example.AlphaTest' -Pshardpilot.count=1 -Pshardpilot.index=1
```

Omitting both properties has the same effect unless the DSL or another source of Gradle project
properties overrides the defaults. `--tests` does not bypass ownership filtering. With multiple
shards, the selected class may belong elsewhere and Gradle can report no matching tests.

## Timing history

Each record contains a binary class name, one literal tab and a nonnegative finite duration in
seconds. The gaps in this example are tabs:

```text
# Representative timings, not a complete consumer test suite.
example.AlphaTest	10.0
example.AlphaTest$Nested	2.0
example.BetaTest	8.0
```

Blank lines and full-line `#` comments are allowed. Names use dots between packages and `$` for
nested classes. Duplicate class names, malformed names, additional fields, missing fields,
negative values, `NaN` and infinity are rejected. Parser errors identify a line without printing
the arbitrary input record. Duration aggregation overflow also fails instead of producing a plan.

Unset or missing history is a supported hash-only cold start. Empty history has the same assignment
effect. A malformed existing file is not treated as a cold start, even with `shardCount = 1`.

Keep one history file per independently planned task/project. All shards of that task must use
the same contents. History is read, not modified, by ShardPilot. Remove stale records through your
own history maintenance process; they still influence packing even when their classes no longer
belong to the suite. See the [assignment algorithm](architecture.md#assignment-algorithm).

## Optional class manifests (0.3.0 and later)

`shardPilot.verification { ... }` adds preparation, actual-selection and aggregation tasks to one
existing `Test` task. Supply `eligibleClasses` as an unsharded tree with the application's discovery
rules; bytecode roots default to the owner's `testClassesDirs`. The preparation count may be fixed
independently of the current CI index. See the [complete option reference](shard-verification.md#configuration-reference).

For lazily declared tasks, call `tasks.named('integrationTest', Test).get().shardPilot.verification`
from project configuration, outside the owner's deferred registration/configuration action. Register
this API once per owner. The [Groovy/Kotlin examples](../README.md#optional-class-manifest-verification)
show both forms and layout-provider overrides.

![Tasks and dependencies for optional verification](diagrams/manifest-tasks.svg)

Normal `integrationTest` execution writes its actual manifest automatically. Preparation and
aggregation remain explicit CI steps. Disjointness/coverage checks do not import JUnit reports or
claim methods succeeded; retain every test job as a required gate.

### JSON and HTML report paths

Verification writes both `coverage.json` and `coverage.html` under the owning task's default
`build/shardpilot/<task>/` namespace. Configure the output properties independently; moving JSON
does not move HTML, and changing `plannedDirectory`/`actualDirectory` does not relocate reports.
Give the two formats distinct file paths. These settings belong to the verification block,
not the plugin's sharding/count project properties.

Groovy DSL, inside the owner's `shardPilot.verification` block:

```groovy
reportFile.set(layout.buildDirectory.file('reports/shards/coverage.json'))
htmlReportFile.set(layout.buildDirectory.file('reports/shards/index.html'))
```

Kotlin DSL, inside the same typed verification action:

```kotlin
reportFile.set(layout.buildDirectory.file("reports/shards/coverage.json"))
htmlReportFile.set(layout.buildDirectory.file("reports/shards/index.html"))
```

The reports use the same manifest snapshot and are written on validation success or failure.
There is no separate HTML task to schedule. Run `verify<Task>Shards` after downloading the evidence
and publish both outputs even if it fails. Standalone aggregation does not compile or run tests.
The HTML file opens locally without scripts, network resources or a web server.
See [HTML statuses](shard-verification.md#reading-html-statuses) and
[GitLab publication](shard-verification.md#finding-a-class-assignment-in-gitlab).

## Multiple projects and tasks

Apply the plugin explicitly in each participating subproject, or through your own convention
plugin. ShardPilot does not configure sibling projects or create a global cross-project plan.
An invocation such as `:api:test` plans that task independently of `:worker:test`.

The global `-Pshardpilot.count` and `-Pshardpilot.index` conventions apply to every configured
`Test` task. Consequently, `check` can run several independently sharded tasks if several have
opted in. Plan the CI matrix accordingly, including any other verification tasks wired to `check`.
Manifest directories and sibling task names are local to their owning project/task. Give explicit
directory overrides separate namespaces rather than mixing artifacts from independent suites.

Existing file includes and excludes continue restricting the suite. Framework annotations,
tags, test discovery and method selection remain with Gradle and the chosen framework. The
plugin does not remove dependencies or helper classes from the runtime classpath.
