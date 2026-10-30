# Architecture and assignment

[Documentation index](README.md)

ShardPilot integrates with standard Gradle `Test` tasks and Maven Surefire/Failsafe goals. Its
shared core calculates an immutable ownership plan from timing history and shard count. Each
adapter excludes candidate classes owned by other shards through its native runner's filters;
the build tool and test framework still discover and execute tests.

## Source modules and artifact compatibility

![Shared core and build-tool adapters](diagrams/modules.svg)

[PlantUML source](diagrams/modules.puml)

| Source module | Responsibilities / distribution |
| --- | --- |
| `shardpilot-core` | Java 17 code with no Gradle/Maven APIs: TSV history, secure JUnit XML timing import, complete-family refresh, ownership, YAML encoding, shared preparation, validation snapshots and reports. |
| `shardpilot-gradle-plugin` | Gradle `Test` integration, process options, lazy filters and managed artifact tasks. Publishes the existing `io.github.sjacek:shardpilot` implementation and unchanged plugin marker. Core classes are bundled into this JAR with their original Java package. |
| `shardpilot-maven-plugin` | Native Maven goals and effective Surefire/Failsafe class-filter adapter. Resolves `shardpilot-core` as a normal plugin dependency; no Gradle libraries are needed. |

The root Gradle build aggregates core/Gradle checks and distribution artifacts. The root Maven POM
aggregates core/Maven tests and artifacts. The core source/test trees are shared by both builds,
with separate `build/` and `target/` outputs. No assignment algorithm or manifest/report serializer
is copied into either adapter. Neither adapter adds dependencies to consumer test workers.

Existing Gradle settings still include the repository root; composite plugin resolution finds
the adapter subproject. The Plugin Portal ID, public Java API/DSL, task names and implementation
coordinates remain stable. A distribution-JAR TestKit regression checks core availability without
placing a separate core artifact on the consumer classpath.

The Maven adapter reads one runner's effective model, merges its matching execution configuration,
resolves expressions and delegates class-pattern matching to Surefire. It writes exclusions before
native workers and records that selected set. Standalone verification uses the same core snapshot
and report writers as Gradle and never manufactures missing artifacts. See the
[Maven lifecycle and boundaries](maven.md).

## Gradle adapter components

![ShardPilot components and Gradle integration](diagrams/components.svg)

[PlantUML source](diagrams/components.puml)

| Component | Responsibility |
| --- | --- |
| `ShardPilotPlugin` | Lazily configures `Test` tasks, attaches the task extension, inputs, exclusion spec and preparation action. |
| `ShardPilotExtension` | Holds per-task sharding properties and applies an explicit test-process configuration action. |
| `TestProcessOptions` | Resolves and validates optional process settings at configuration time, then uses public Gradle `Test` setters and declares inputs. |
| `DurationHistory` | Validates and parses timing records into immutable class-duration metadata. |
| `ShardPlan` | Groups nested classes, packs measured families and resolves ownership for any binary class name. |
| `ShardClassFilter` | Lazily obtains the plan and excludes non-owned candidate `.class` files. |
| `PrepareShardAction` | Uses the plan for validation and metadata-only lifecycle logging before test execution. |
| `ShardVerificationOptions` / `ShardVerification` | Accept explicit unsharded eligibility and register the optional sibling tasks during configuration. |
| `PrepareShardManifests` | Compile through source-set dependencies, reuse the planner and write one baseline plus all partitions. |
| `WriteShardManifest` | Persist the owner's real candidate set before workers execute. |
| `VerifyShardManifests` | Inspect downloaded evidence on every invocation and emit success/failure JSON and HTML without rerunning producers. |
| `ShardCoverageHtml` | Render a standalone assignment table and partial-evidence diagnostics from the same snapshot used for JSON and validation. |
| `ClassManifest` / `ShardCoverage` | Encode strict binary-name records and check partition/index consistency independently of Gradle execution. |

The filter and action are serializable and do not capture a `Project` or `Test` instance. The
filter caches its plan transiently; after configuration-cache restoration it can rebuild the
plan from provider values. Plans are local to task execution, not shared through a remote service.

The optional `testProcesses` action is executed during task configuration, independently of
shard filtering. It changes only supplied process settings. There is no execution-time process
configuration, replacement scheduler or captured task in an execution action. Gradle owns the
actual JVMs and class-boundary recycling. Explicit process options are additional task inputs.

The optional verification action registers typed sibling tasks outside deferred task-configuration
actions. Preparation and execution reuse `DurationHistory`/`ShardPlan`; no second assignment algorithm
exists in the consumer. Only the actual writer becomes an owning-test dependency. Verification has
ordering constraints, not producer dependencies, so it cannot replace missing downloads with new files.

During verification, `ShardCoverage` reads every expected manifest into one immutable evaluation
snapshot, retaining valid sets and read/validation failures. Validation produces the gate outcome
and compact JSON; `ShardCoverageHtml` renders counts, assignments and available diagnostics from
that same snapshot. A failed early file does not hide readable later partitions. Plan-only mode
does not read actual files. Extra indexed filenames are checked separately and remain gate errors.

![Snapshot, reports and independent CI outcomes](diagrams/html-report.svg)

[PlantUML source](diagrams/html-report.puml)

`VerifyShardManifests` writes both configured outputs before raising a validation error. Output I/O
can fail the task and is not atomic across the two files; the Gradle outcome remains authoritative.
HTML is a pure presentation artifact with embedded styles, no JavaScript or remote resources.
Publishing it in an MR is consumer-owned CI wiring, with no GitLab API client or credentials in
the plugin. See the [report contract](shard-verification.md#html-assignment-report).

![Optional manifest task graph](diagrams/manifest-tasks.svg)

[PlantUML source](diagrams/manifest-tasks.puml) · [Manifest format and lifecycle](shard-verification.md)

## Assignment algorithm

![Timing history to class ownership](diagrams/shard-assignment.svg)

[PlantUML source](diagrams/shard-assignment.puml)

For an enabled task, validate that count is positive and index is in `1..count`. Parse the optional
TSV history; no file means no measured records. The planner then performs these steps:

1. Sort records by binary class name before summing them. This fixes floating-point addition order.
2. Convert each binary name to its family: the part before the first `$`. Add nested record durations
   to the same outer family, rejecting non-finite totals.
3. Sort families by descending total duration, then ascending family name for equal weights.
4. Assign each measured family to the shard with the lowest recorded load. Equal loads choose the
   lowest one-based index. Reject overflow of a shard's accumulated load.
5. For a candidate absent from the measured plan, calculate its family owner with
   `Math.floorMod(family.hashCode(), shardCount) + 1`.

Java's string hash is used, not `Math.abs(hash) % count`; the latter mishandles the minimum integer
hash. Unknown nested classes still use the outer family's measured owner when one exists.

### Worked example

Given two shards and records `AlphaTest = 10`, `AlphaTest$Nested = 2`, `BetaTest = 8`,
`GammaTest = 6` and `DeltaTest = 4`, the measured families are packed as follows:

| Step | Family | Seconds | Chosen shard | Loads after assignment: shard 1 / shard 2 |
| --- | --- | --- | --- | --- |
| 1 | `AlphaTest` | 12 | 1 | 12 / 0 |
| 2 | `BetaTest` | 8 | 2 | 12 / 8 |
| 3 | `GammaTest` | 6 | 2 | 12 / 14 |
| 4 | `DeltaTest` | 4 | 1 | 16 / 14 |

All `AlphaTest$…` classes stay with shard 1. A newly added family without a timing record uses
the hash fallback; its runtime is not added to the recorded loads. Recorded loads therefore do
not predict the final job duration.

There is no guarantee that every shard receives a class. For example, measured families with
zero durations always tie and are assigned to shard 1. Stale or excluded timing records remain
part of the plan and may distort balance, but cannot make Gradle execute an excluded class.

## Candidate filtering, not a replacement runner

The installed spec is an **exclusion**: returning `true` means that a candidate belongs to a
different shard. Adding a separate include spec could widen an existing include selection;
excluding non-owners instead preserves the consumer's original file restrictions.

When sharding is disabled, the spec excludes nothing. With count 1 it still validates the enabled
plan, then excludes nothing. Directories and non-class files are not excluded by ShardPilot.
The runtime classpath stays intact, so superclass and helper-class loading remain available.

After candidate filtering, the existing Gradle/framework selection still applies. No test method
is moved independently of its class family, no retry is added, and no test failure or explicit
no-matching-tests error is suppressed.

## History refresh boundary

![History evidence, conservative normalization and reviewed adoption](diagrams/history-refresh.svg)

[PlantUML source](diagrams/history-refresh.puml)

`JUnitXmlTimings` reads local `TEST-*.xml` metadata securely; `DurationHistoryRefresh` applies
shared family/duplicate/pruning rules and produces `HistoryRefreshReport` plus candidate records.
The Gradle `RefreshShardDurations` task and Maven `RefreshDurationsMojo` only adapt paths, source
selection, failures and output I/O. Neither copies normalization logic or schedules producers.
The Gradle task is opt-in/cacheable; Maven's standalone goal is thread-safe and module-local.
The consumer owns cohort provenance and reviewed adoption. Refresh JSON measures recorded
history loads, while manifest JSON/HTML describes class selections; neither proves tests passed.
See the [refresh contracts](history-refresh.md) and [CI flow](ci.md#refresh-history-from-collected-reports).

## Inputs, cache and logs

Each `Test` task declares these inputs: `shardpilot.enabled`, `shardpilot.count`,
`shardpilot.index` and `shardpilot.history`. The last is history **contents**, not an absolute path.
They distinguish the task's shard configuration for up-to-date/build-cache checks. Consumers still
decide whether tests depending on mutable external resources may reuse cached results.
An explicit test-process block adds inputs for the supplied options:
`shardpilot.testProcesses.maxHeapSize`, `shardpilot.testProcesses.maxParallelForks` and/or
`shardpilot.testProcesses.forkEvery`. Omitted options add no process-policy input.

When the preparation action runs, it emits a Gradle lifecycle message shaped as:

```text
ShardPilot :test: shard 1/2, 4 recorded class families, 16.0 recorded seconds.
```

The family count describes the whole measured plan; seconds describe the selected shard's
recorded load. Neither includes unknown runtimes, JVM startup or container provisioning. Cached,
up-to-date or no-source tasks need not execute this action, so the message is not a completion
record for every invocation.

Preparation snapshots eligible bytecode, roots, count and history; actual writing snapshots candidates,
roots and execution count/index. These tasks may reuse exact-input cached/up-to-date results.
Coverage verification is always executed and is not build-cacheable, including after Configuration
Cache restoration. Its checks operate on the downloaded files, not test-method results.

The plugin does not read test output, upload artifacts, validate successful completion across jobs
or maintain central state. Optional class manifests verify a supplied artifact cohort; CI owns its
provenance and requires every normal test outcome. See [CI integration](ci.md).
