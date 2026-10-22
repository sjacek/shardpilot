# Configuration

[Documentation index](README.md)

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

## Groovy DSL

```groovy
plugins {
    id 'java'
    id 'io.github.sjacek.shardpilot' version '0.1.0'
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
    id("io.github.sjacek.shardpilot") version "0.1.0"
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

## Multiple projects and tasks

Apply the plugin explicitly in each participating subproject, or through your own convention
plugin. ShardPilot does not configure sibling projects or create a global cross-project plan.
An invocation such as `:api:test` plans that task independently of `:worker:test`.

The global `-Pshardpilot.count` and `-Pshardpilot.index` conventions apply to every configured
`Test` task. Consequently, `check` can run several independently sharded tasks if several have
opted in. Plan the CI matrix accordingly, including any other verification tasks wired to `check`.

Existing file includes and excludes continue restricting the suite. Framework annotations,
tags, test discovery and method selection remain with Gradle and the chosen framework. The
plugin does not remove dependencies or helper classes from the runtime classpath.
