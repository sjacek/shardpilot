# Configurable selection: core contract v1

[Documentation index](README.md)

**Development checkout, 14 November 2026.** The shared Java engine is implemented in
`shardpilot-core`. The development adapters now expose profile parameters and shared Git acquisition;
these additions are not present in published **0.5.0**. Existing
[explicit-manifest selection](test-selection.md) keeps its current behavior.

The core takes immutable inputs, evaluates configured application knowledge and returns a
decision. It invokes no Git command, build tool, test runner, remote service or consumer callback.
Required Java parameters use JSpecify non-null contracts. Construct configuration with
`SelectionPolicy` records or parse the same versioned configuration with `SelectionPolicyYaml`.

![Selection policy evaluation](diagrams/selection-policy.svg)

[PlantUML source](diagrams/selection-policy.puml)

## Policy parameters

This is a shortened version of the
[executable policy fixture](https://github.com/sjacek/shardpilot/blob/main/shardpilot-core/src/test/resources/selection-policy-testdata.yaml).
Profile names are consumer-defined; `daily-testdata` is a synthetic fixture profile.

```yaml
schemaVersion: 1
profiles:
  full-testdata:
    mode: full
  smoke-testdata:
    mode: smoke
    mandatory:
      classes: [example.LoginIT]
  explicit-testdata:
    mode: explicit
    mandatory:
      classes: [example.LoginIT]
  daily-testdata:
    mode: affected
    mandatory:
      classes: [example.LoginIT]
    budget:
      maxFamilies: 3
      maxSeconds: 90
      unknownSeconds: 30
      overflow: fail
    onUnknownChanges: full
    onUnmatchedChanges: full
    onEmptySelection: fail
    rules:
      - id: payments
        changes: ['src/payments/**', 'contracts/payments/**']
        action: require
        tests:
          patterns: ['example.payments.**.*IT']
      - id: compatibility-probes
        changes: ['src/payments/**']
        action: optional
        priority: 10
        tests:
          classes: [example.CompatibilityIT]
      - id: build
        changes: ['build.gradle', 'pom.xml']
        action: full
      - id: documentation
        changes: ['docs/**', '*.md']
        action: ignore
```

| Parameter | Contract / default |
| --- | --- |
| `schemaVersion` | Required integer `1`. Unknown versions fail. |
| `profiles` | Required map of 1–64 independent profiles. IDs use ASCII letters/digits, dot, underscore or hyphen; start with a letter/digit; maximum 80 characters. Rule IDs use the same syntax. |
| `mode` | Required: `full`, `smoke`, `affected` or `explicit`. |
| `mandatory` | Protected `classes` and/or `patterns`; defaults to empty. |
| `rules` | Only `affected` accepts rules. Empty by default; maximum 256, with unique IDs. |
| Rule `changes` | Required nonempty sequence of repository-relative path globs; maximum 256. Any matching pattern activates the rule. |
| Rule `action` | Required: `require`, `optional`, `full` or `ignore`. |
| Rule `tests` | Required nonempty targets for `require`/`optional`; empty for `full`/`ignore`. |
| Rule `priority` | Nonnegative integer, default `0`. Only `optional` permits a nonzero value; smaller values are considered first. |
| `budget.maxFamilies` | Nonnegative integer; `0` means unlimited. Counts outer families, not nested members. |
| `budget.maxSeconds` | Finite nonnegative estimate; `0` means unlimited. |
| `budget.unknownSeconds` | Finite positive substitute per unmeasured family; default `60`. |
| `budget.overflow` | `fail` (default) or `keep-required`. Never removes protected targets. |
| `onUnknownChanges` | `full` or `fail` when change acquisition is unavailable. |
| `onUnmatchedChanges` | `full` or `fail` when at least one changed path has no rule. |
| `onEmptySelection` | `full` or `fail` when a nonempty eligible suite would produce no selected tests. |

All three fallbacks default to `full` in `affected` and `fail` in other modes. The Java factory
methods `Profile.full()`, `.smoke(...)`, `.affected(...)` and `.explicit(...)` use these defaults.
No profile inheritance or executable expressions are supported. Unknown fields, duplicate keys
or sequence values, collection aliases, unsafe tags and incorrect scalar types fail parsing.
The parser bounds input to 1,048,576 UTF-16 characters, 100,000 events and 16 collection levels.
`SelectionPolicyYaml.read(path)` additionally bounds the file to 1,048,576 UTF-8 bytes before decoding.

Targets accept at most 4,096 exact binary names and 256 class patterns. Each name/pattern is
bounded to 512 characters. Exact names must occur in full eligibility **E**; every configured
pattern must match at least one member of E, even in a rule not activated by the current diff.
Stale configuration fails instead of silently reducing intended coverage.

## Matching and precedence

Paths use `/` and class patterns use `.` as segment separators. `*` matches zero or more
characters inside one segment, `?` matches one Unicode code point, and a whole `**` segment
matches zero or more segments. Thus `**/*.md` matches `README.md`, and
`example.**.*IT` matches both `example.TransferIT` and `example.payments.TransferIT`.
Other characters are literal; patterns are not regular expressions. Nested binary names use `$`.
Absolute paths, backslashes, empty/dot/parent segments, colons and control characters are rejected.
Actual paths may contain spaces and Unicode; each path is bounded to 4,096 characters.

1. Validate the selected profile, all its targets and all history records against input contracts.
2. Add mandatory families. `full` selects all E; `smoke` uses mandatory targets;
   `explicit` adds the supplied exact names, all protected.
3. In `affected`, union **all** matching rules. `require` protects targets; `optional` proposes
   budget-limited targets. `ignore` classifies a path without suppressing overlapping rules.
4. An activated `full` rule selects all E and takes precedence over unmatched-path fallback.
   Otherwise apply declared unknown/unmatched-change policies before budget packing.
5. Reserve all protected families. Consider optional families by priority, cost, then binary
   outer name. Shared optional targets use the smallest matching priority. Required membership
   always wins over optional membership. Every excluded optional family is reported as deferred.
6. If nothing remains, apply `onEmptySelection`. Empty eligibility, an unknown profile, an empty
   explicit request or a foreign explicit name always fails; these never produce empty success.

A family includes **only existing eligible members**. Selecting `Outer$Nested` also selects
eligible siblings/outer members, without inventing an absent `Outer.class`. Full runs and full
fallbacks intentionally bypass budget truncation and report any exceeded limit.

## Timing budgets

Costs sum history records belonging to each outer family, including nested timing records,
using deterministic decimal arithmetic. All supplied history values must be finite and nonnegative,
including records outside E. Missing families use `unknownSeconds` once per family and are marked
`UNKNOWN_ESTIMATE`. Eligibility, explicit requests and history each allow at most 100,000 records;
change sets allow at most 20,000 paths. The matcher has a per-decision 20-million-step ceiling;
exhaustion produces a failed decision, never a truncated successful subset.

With `overflow: fail`, protected tests that exceed a budget produce failed evidence and no
executable subset. With `keep-required`, all protected families remain and the overflow is explicit.
Optional work fits only when **both** configured limits permit it. Family/selected-cost overflow
beyond finite double reporting fails. These estimates are not wall-clock timeouts: startup,
containers, workers and concurrency remain native runner responsibilities.

## Java API and adapter boundary

```java
SelectionPolicy policy = SelectionPolicyYaml.parse(policyText);
SelectionContext inputs = new SelectionContext(
    "daily-testdata",
    eligibleBinaryNames,                 // full E, independently discovered
    SelectionContext.ChangeSet.known(changedRepositoryPaths),
    DurationHistory.parse(historyTsv),
    Set.of());                           // exact requests for explicit mode only
SelectionDecision decision = SelectionEngine.select(policy, inputs);
decision.write(outputDirectory);         // dedicated generated directory
if (decision.status() == SelectionDecision.Status.FAILED) {
    throw new IllegalStateException("Selection planning failed; inspect decision.json");
}
```

`ChangeSet.unknown()` represents unavailable acquisition, which differs from a known empty diff.
Adapters supply both old/new paths for renames/deletions. The core does not infer them. A `ChangeSet`
can retain full lowercase 40/64-character Git commit IDs; known Git changes require both IDs,
while non-Git path sets can omit both. Unavailable acquisition can retain whichever ID is known.

`SelectionEngine.select` has no I/O or mutable global state. Shape violations throw
`IllegalArgumentException`; valid-shaped inputs that cannot produce an accepted selection return
`FAILED` with stable reasons. A successful decision has immutable S, with **S ⊆ E**, ready for
the existing explicit manifest boundary. Core regression tests read that manifest through
`TestSelection` and partition it with `ShardPlan` into 1, 2 and 4 non-overlapping shards.
The development adapters consume this result through the existing native selection boundary.

## Decision artifacts and identity

`write(directory)` produces `decision.json`, an escaped standalone `decision.html` and
`eligible.yaml`. Success also writes `selected.yaml` (existing class-manifest v1) and `decision.id`.
It removes any previous `selected.yaml`/`decision.id` before writing, so failure cannot leave an
older executable subset. Use a dedicated generated directory, separate from maintained inputs;
the adapters publish those artifacts and preserve native build failure handling.

JSON schema/engine versions are `1`. Families carry eligible members, selected/deferred/unrelated/
blocked disposition, protected status, measured/substituted cost, matching rules and reason codes.
The report retains unmatched paths and invalid target names. Failed plans have no selected tests
and a null accepted cost. Failure may stop classification early; `NOT_SELECTED` then also includes
families not yet evaluated. Do not put secrets or personal data in policy IDs, paths or test names.

`policySha256` hashes canonical semantic YAML: map/list ordering and comments do not matter;
changing any profile, including an unused one, changes the policy hash. `identity` additionally
binds profile, E, change availability/paths/base/head, explicit requests and complete history using
sorted, length-framed values. It is independent of shard count, which belongs to downstream plans.
This is a new planning identity, separate from the 0.5.0 raw-manifest hash and `selection.id`.
It proves repeatable planning, not execution. The
[C2 execution-evidence contract](execution-evidence-contract.md) still needs its own implementation
and reviewed binding to automatic-selection provenance.


## Gradle and Maven parameters (development checkout)

Configure independent native eligibility once. No executable selector callback or external shell
script is needed. Gradle still requires explicit opt-in for each owning `Test` task; supplying
properties alone does not activate every test task. Maven retains explicit Surefire/Failsafe
exclusion/report wiring and native failure gates.

| Common parameter after `shardpilot.` | Gradle selection DSL | Maven POM element | Default |
| --- | --- | --- | --- |
| `selectionPolicyFile` | `policyFile` | `selectionPolicyFile` | Unset; paired with profile. |
| `selectionProfile` | `profile` | `selectionProfile` | Unset; required named profile. |
| `selectionManifestFile` | `manifestFile` | `selectionManifestFile` | Exact requests for `explicit`; existing manifest-only behavior is retained. |
| `selectionSuite` | `suite` | `selectionSuite` | `profile-NAME` for profiles; explicit manifests still require a suite. |
| `selectionSnapshotDirectory` | `snapshotDirectory` | `selectionSnapshotDirectory` | Unset; required for multi-node profile execution. |
| `gitDirectory` | `gitDirectory` | `gitDirectory` | Current project directory, or a directory within the checkout. |
| `gitBase` | `gitBase` | `gitBase` | Supported CI inference; otherwise UNKNOWN. |
| `gitHead` | `gitHead` | `gitHead` | `HEAD`; must resolve to the actual checkout. |
| `gitMergeBase` | `gitMergeBase` | `gitMergeBase` | `true`; `false` compares base/head directly. |
| `gitFetch` | `gitFetch` | `gitFetch` | `false`; offline mode always prevents fetching. |
| `gitRemote` | `gitRemote` | `gitRemote` | `origin`; configured name, never a URL. |
| `gitFetchDepth` | `gitFetchDepth` | `gitFetchDepth` | `256`; allowed 1–100,000. |
| `gitTimeoutSeconds` | `gitTimeoutSeconds` | `gitTimeoutSeconds` | `30` per command; allowed 1–300. |

Use `-Pshardpilot.NAME=value` with Gradle and `-Dshardpilot.NAME=value` with Maven.
Gradle CLI properties override the opted-in task's DSL settings. For Maven POM settings that
must accept CLI overrides, define a property default and reference `${shardpilot.NAME}` in
configuration, as the runnable example does; a literal POM value follows Maven's usual precedence.
Keep the same policy, profile, explicit requests, history, eligibility, suite and count on every node.
Profile `full` has namespace `profile-full`, distinct from the reserved legacy `full` suite.

### Gradle Groovy DSL

```groovy
// test uses the project's normal native framework/includes/excludes and enables shardPilot.
def eligible = sourceSets.test.output.classesDirs.asFileTree.matching {
    include '**/*Test.class'
    exclude '**/*$*.class'
}
def pilot = tasks.named('test', Test).get().shardPilot
pilot.enabled = true
pilot.selection {
    policyFile = layout.projectDirectory.file('policy.yaml')
    profile = 'affected-testdata'
    eligibleClasses.from(eligible)
}
pilot.verification { eligibleClasses.from(eligible) }
```

### Gradle Kotlin DSL

```kotlin
import io.github.sjacek.shardpilot.ShardPilotExtension

val eligible = sourceSets.test.get().output.classesDirs.asFileTree.matching {
    include("**/*Test.class")
    exclude("**/*$*.class")
}
val pilot = tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>()
pilot.enabled.set(true)
pilot.selection {
    policyFile.set(layout.projectDirectory.file("policy.yaml"))
    profile.set("affected-testdata")
    eligibleClasses.from(eligible)
}
pilot.verification { eligibleClasses.from(eligible) }
```

Both runnable consumers are covered by TestKit:
[Groovy](https://github.com/sjacek/shardpilot/blob/main/examples/gradle/junit-jupiter/selection-profiles/README.md),
[Kotlin](https://github.com/sjacek/shardpilot/blob/main/examples/gradle/junit-jupiter/selection-profiles-kotlin/README.md).

### Maven POM

```xml
<properties>
  <shardpilot.selectionProfile>smoke-testdata</shardpilot.selectionProfile>
</properties>
<!-- Inside the existing ShardPilot plugin configuration/execution: -->
<configuration>
  <selectionPolicyFile>${project.basedir}/policy.yaml</selectionPolicyFile>
  <selectionProfile>${shardpilot.selectionProfile}</selectionProfile>
</configuration>
<!-- Inside Surefire, in addition to its normal settings: -->
<configuration>
  <excludesFile>${project.build.directory}/shardpilot/unit/selections/profile-${shardpilot.selectionProfile}/excludes.txt</excludesFile>
  <reportsDirectory>${project.build.directory}/shardpilot/unit/selections/profile-${shardpilot.selectionProfile}/test-results/${shardpilot.index}</reportsDirectory>
</configuration>
```

The [runnable Maven consumer](https://github.com/sjacek/shardpilot/blob/main/examples/maven/selection-profiles/README.md)
uses independent native discovery and verifies native XML from both nodes. Failsafe uses its
integration namespace and retains both `integration-test` and native `verify` goals.

## Git acquisition and safe fallback

Only `affected` reads Git. Other modes work without a checkout. Changes are committed paths
relative to the repository root, even when `gitDirectory` points inside a module. Rename/copy
records retain old and new names; deletions retain the old name. NUL-delimited output preserves
spaces and Unicode. Commands use argument arrays, validated refs, bounded output/time and no shell.
The comparison defaults to the merge base, so earlier commits in a multi-commit MR are included.

Explicit `gitBase` wins. Otherwise the supported inference order is:

1. GitLab MR `CI_MERGE_REQUEST_DIFF_BASE_SHA`, then target branch under the configured remote.
2. GitLab push `CI_COMMIT_BEFORE_SHA`.
3. GitHub PR payload `pull_request.base.sha`, or `GITHUB_BASE_REF` under the configured remote;
   GitHub push payload `before` from `GITHUB_EVENT_PATH`.

No base, zero before-SHA on a new branch, missing/shallow/unrelated history, an unavailable Git
executable, malformed acquisition input or an uncommitted checkout produces **UNKNOWN**, never
a successful empty diff. The profile then applies `onUnknownChanges: full|fail`. There is no
implicit `HEAD^` fallback. A different explicit head is an error. Known empty diffs use the
profile's normal mandatory/empty-selection policy, distinct from UNKNOWN.

This stage deliberately supports **committed-only** impact: tracked edits and untracked files
make the checkout UNKNOWN. Ignore generated `build/`, `target/` and downloaded artifact folders
in the consumer repository. Working-tree impact, structured contract diffs and automatic module
dependency inference are not implemented. Fetch is opt-in, uses a configured remote with bounded
depth and never runs offline. Prefer a checkout with adequate history in CI. Git stderr, credential
URLs and arbitrary subprocess error bodies are excluded from logs and evidence; `git.json` retains
stable source/failure codes and exact known revisions/paths.

## Freeze once, execute every node

For Gradle, `prepareTestShardManifests` first runs `acquireTestGitChanges`, then
`prepareTestSelection`. Acquisition always observes current refs/CI/worktree at task execution,
including when configuration cache is reused; deterministic snapshot bytes feed cacheable
planning. A one-node `test` may prepare its profile automatically.

For Maven, run `test-compile shardpilot:prepare` once. A single-node lifecycle-bound `select`
may prepare automatically. Multi-node `test`/`select` requires a downloaded common snapshot in
both adapters. It cannot independently re-read branch tips and generate a new subset per node.

Retain `selection/` together with the common `plan/` directory. Download the complete selection
to a **different, ignored input directory**, e.g. `build/downloaded-selection` or
`target/downloaded-selection`. Supply its path using `selectionSnapshotDirectory` to every worker.
Transporting ordinary CI artifacts is still the CI system's responsibility; it contains no
selection algorithm. A local Gradle example sequence, after the user makes the snapshot available:

```shell
./gradlew prepareTestShardManifests -Pshardpilot.count=2 -Pshardpilot.selectionProfile=affected-testdata -Pshardpilot.gitBase=main
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=1 -Pshardpilot.selectionProfile=affected-testdata -Pshardpilot.selectionSnapshotDirectory=build/downloaded-selection
./gradlew test -Pshardpilot.count=2 -Pshardpilot.index=2 -Pshardpilot.selectionProfile=affected-testdata -Pshardpilot.selectionSnapshotDirectory=build/downloaded-selection
./gradlew verifyTestShards -Pshardpilot.count=2 -Pshardpilot.selectionProfile=affected-testdata
```

Workers recompute the pure decision from frozen changes and current policy/E/history, verify all
retained decision artifacts and cohort identity, then verify local HEAD and clean state for a known
affected selection. They do **not** diff, fetch or query the base branch. Frozen-input validation
runs even when native test outputs are up to date. Corruption or changed inputs fail before workers;
no fallback to a different selection is allowed. Keep the input outside all generated selection,
plan and actual output directories; overlapping directories and canonical parent aliases fail.

The standard outer `selection.id` remains compatible with explicit selection. Profile preparation
adds policy/decision and Git-provenance hashes to its internally generated source-manifest comments,
so the existing raw-manifest digest binds them, plus full E, raw history and shard count. Native
selected manifests remain canonical class-manifest v1. Inner `selection/decision/` contains
`git.json`, `decision.json`, `decision.html`, `decision.id`, `eligible.yaml` and `selected.yaml`.
Retain the entire directory, not just the selected class list.

Aggregation (`verifyTestShards` / `shardpilot:verify`) remains artifact-only: no Git, compilation,
planning or test rerun. Matching class/selection identities prove a consistent cohort, not that
all native invocations executed successfully. Native failures/skips retain their existing gates;
the separate [C2 execution-evidence validator](execution-evidence-contract.md) remains future work.
