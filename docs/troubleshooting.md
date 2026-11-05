# Troubleshooting

[Documentation index](README.md)

Start with the task path, plugin revision, enabled flag, shard count/index and exact history
snapshot used by the job. Compare the same inputs across shards before changing test framework
configuration. Keep credentials, personal data and raw application output out of public bug reports.

The task/DSL and cache diagnostics below describe Gradle. For Maven, record the lifecycle/goal,
runner (`SUREFIRE`/`FAILSAFE`), suite and module as well; use the
[native-runner checklist](#maven-native-runner-checklist).

## Gradle API imports in IntelliJ IDEA

`ExtensionAware`, `MavenPom`, `MavenPublication`, `PluginDeclaration` and `Provider` are
Gradle API types supplied by the Gradle distribution. If build scripts report unresolved
imports while the Wrapper build succeeds, check the IDE's Gradle model before changing code:

1. Link the repository-root `settings.gradle` as a Gradle project, even if the Maven reactor
   is already imported. Select the Wrapper and a supported Gradle JVM in Gradle settings.
2. Synchronize the linked Gradle projects after changing the Wrapper. Wait for dependency
   resolution and indexing, and inspect any synchronization failure in the Build window.
3. Independent example directories are not root subprojects. Link the example's own
   `settings.gradle` separately; if it has no Wrapper, select the local Gradle installation
   downloaded by the root Wrapper rather than an empty local-distribution setting.
4. Rerun the inspection after synchronization. A previous Code Analysis result is a snapshot
   and can still list errors from before the model was refreshed.

Keep machine-specific JDK, Gradle installation and linked-project paths in local IDE settings,
outside version control. The command-line check remains `./gradlew check assemble`; removing
valid API imports or suppressing unresolved references does not repair an IDE model.

## Selection and execution

| Symptom | Likely cause | Check |
| --- | --- | --- |
| Every job executes the full suite. | Task is not opted in, count is 1, or the DSL pins a convention. | Configure that task's `shardPilot.enabled`; inspect explicit count/index assignments and CLI arguments. |
| A class appears in two jobs, or is absent from their union. | Jobs differ in history, revision, count or original selection; an index is missing/repeated. | Compare immutable inputs and the entire matrix. There is no coordinator to reconcile inconsistent jobs. |
| A shard runs no tests. | It owns no candidates after the existing selection. | Compare all shard results to a count-1 baseline. Empty shards are valid; zero timings can pack all measured families onto shard 1. |
| `--tests` fails with no matching tests. | The matching class belongs to a different shard, or the selector is wrong. | Use count 1/index 1 for focused local execution. Do not disable failure checks globally. |
| `integrationTest` is missing. | No consumer task/source set has registered it. | Register the task in the consumer, then opt it in. ShardPilot only configures `Test` tasks that exist. |
| Plugin ID cannot be resolved. | The version is unavailable, plugin repositories omit the Portal, network access is blocked, or an explicit included-build path is wrong. | Pin a published version and retain `gradlePluginPortal()`; follow [installation](../README.md#getting-started). Only plugin development needs a [local checkout override](../README.md#local-plugin-development). |
| Kotlin DSL cannot find a project-level `shardPilot` block. | The extension belongs to each task. | Use the [typed task extension](configuration.md#kotlin-dsl). |

Do not use a single successful shard as evidence that the suite passed. The CI pipeline must
require every index and retain the consumer's normal test failure policy.

## History and balance

A parsing error names the line but deliberately does not echo its contents. Inspect that line
locally for a literal tab, binary class name and finite nonnegative seconds. Duplicate records are
errors; distinct nested records are allowed and summed with their outer family. An absent file is
a supported cold start, not an error. See the [history format](configuration.md#timing-history).

When the distribution is uneven, compare recorded duration with actual job time. Unknown classes
use hashing, stale records still affect packing, and startup/container costs are not represented
by class timings. One unusually long family cannot be divided at method level. Also check whether
the history producer counted nested durations twice or recorded zeros for every measured class.

The lifecycle message reports **all measured families** and only the **selected shard's recorded
seconds**. It is not the selected test count or a wall-clock forecast. An up-to-date, cached or
no-source task can skip the logging action entirely.

## Duration refresh

Start with the process exit status and the new `durations.json`, using the
[failure/output contract](history-refresh.md#failure-and-adoption-contract). Refresh JSON uses
`PASSED`/`FAILED`; it is separate from class-selection `coverage.json` and `coverage.html`.

| Symptom / reason | Check and next action |
| --- | --- |
| Coverage passes but refresh reports `MINIMUM_COVERAGE` | Actual class manifests can be complete while XML is missing or excluded. Compare indexed node directories and `measured`/`eligible` family counts; follow [same-cohort recovery](history-refresh.md#recover-a-missing-shard-download). |
| `INVALID_BASELINE` | Supply the selected manifest or bytecode roots. A missing selected source never falls back to another source or compiles it automatically. Maven AUTO defaults to the prepared suite baseline. |
| `DUPLICATE_SUITE` | Check for repeated downloads, full-run XML mixed with node XML or another pipeline in the report tree. Retain distinct paths; `SUM` is explicit policy, not proof that two samples are independent. |
| `NO_MEASUREMENTS` | Inspect report roots and exclusion counters. Only skipped, retried or ambiguous families can leave no accepted sample, even with `minimumCoverage=0`. |
| Failed refresh leaves no candidate TSV | Data rejection removes an earlier successful candidate and writes FAILED JSON. Recover the input problem and evaluate a new successful result. |
| A stale JSON remains after a rejected request | Invalid configuration creates no new report. Check invocation exit status and precision/source/output settings before reusing any artifact. |
| Refresh is up-to-date or uses Configuration Cache | Unchanged complete inputs can reuse valid outputs. Restoring missing XML changes task inputs; Configuration Cache reuse does not skip the changed refresh action. |
| A JSON exists but output writing failed | Writes are not an atomic TSV/JSON transaction. Honor the failed build outcome; an existing report alone does not authorize adoption. |

Do not compare testcase totals with `minimumCoverage`: that ratio counts complete eligible
families. Keep native test jobs, selection verification and refresh policy as independent gates,
then review the candidate for a future history commit.

## Cache and reports

Changing history contents, count or index changes declared task inputs, but a task may still reuse
results already available for that exact configuration. To investigate execution locally, use
Gradle's `--info` output and, when a fresh run is needed, `--rerun-tasks`. Review the consumer's
cache policy for mutable external resources separately.

Overlapping reports usually indicate concurrent jobs writing to the same checkout or artifact
downloads overwriting each other. Give each shard an isolated build directory and artifact
namespace. Do not change sharding logic to compensate for shared output paths.

For a configuration-cache failure, keep the stack trace and identify the object being captured.
Reproduce with strict cache settings. The built-in filter/action avoid capturing `Project` and
`Test`; a consumer callback or another plugin may not. Do not disable strict checks merely to
declare the integration compatible.

## Manifest verification

![Validation and failure reporting](diagrams/manifest-validation.svg)

[PlantUML source](diagrams/manifest-validation.puml) · [Manifest schema](shard-verification.md#manifest-format)

| Symptom | Check / action |
| --- | --- |
| A generated sibling task cannot be found, or Gradle rejects registration in the current context. | Configure verification once outside deferred task actions, with an explicitly realized owner; use the [DSL examples](../README.md#optional-class-manifest-verification). |
| `Missing class manifest`. | Download the baseline, all planned YAML files and every actual node artifact from the same pipeline. Empty files are not placeholders. Aggregation deliberately does not regenerate evidence. |
| Invalid YAML, unsupported schema or wrong field types. | Require one mapping with integer `schemaVersion: 1` and a `classes` sequence of strings. Reject unknown fields, duplicate keys and nonstring names. Regenerate old `.txt` artifacts. |
| Empty baseline or empty shard. | Eligibility must be nonempty. A legitimate empty partition needs `classes: []` and explicit `requireNonEmptyShards=false`; normal test failure policy still applies. |
| Classes overlap, are missing or are foreign. | Compare source revision, discovery restrictions, count and history across the entire run. Reuse the application's eligibility rules for the unsharded baseline. |
| `Shard N differs from plan`. | Inspect that index's planned and actual class lists. Swapped node artifacts can have the correct overall union and still fail this check. |
| Unexpected indexed filenames. | Restore a clean cohort containing exactly indices 1..N. Avoid leftovers from a different count/run and preserve the expected `.yaml` names. |
| An earlier green report became a failed report. | Verification always inspects current evidence. Keep failure JSON/HTML and investigate the new inputs instead of accepting a previous result. |
| Coverage passed but a test failed or was skipped. | Coverage proves class selection only. CI must also require all ordinary test outcomes and handle skips under the consumer's policy. |
| A class-selection hash changed. | Compare names and ownership. YAML formatting, comments and CRLF alone do not change the digest; history changes can change ownership. |

Preparation rejects malformed history, invalid count and empty eligibility before modifying an
existing valid plan. That does not make multi-file writes atomic: an I/O failure needs a fresh,
successful preparation run. Retain actual evidence after ordinary test failure where possible.

## HTML report and GitLab links

| Symptom | Check / action |
| --- | --- |
| JSON moved but HTML is at its old location. | Set `htmlReportFile` explicitly; `reportFile`, manifest directories and HTML location are independent. Update the artifact path to match. |
| No HTML after invoking only preparation or a normal test shard. | HTML is an output of `verify<Task>Shards`. The prepare/actual writers only record YAML evidence. |
| An HTML row says `Matches`, but verification failed. | Read the global error. Per-index/per-class equality does not establish a complete, disjoint partition or an exact artifact filename set. |
| A missing file is shown as `Unavailable`. | Restore the matching artifact cohort. Do not replace missing evidence with an empty YAML file or treat `None` assignments as proof of omission. |
| Verification was skipped after a failed test node. | `artifacts:when: always` affects uploading only. Schedule the coverage/publisher jobs after failure too, keeping every normal test result required. Check job `rules` as well as `when`. |
| The publisher fails because HTML is missing. | Check the coverage job's start, Gradle/plugin resolution, logs, `htmlReportFile`, write permissions and uploaded artifact paths. An early startup/I/O failure may prevent report generation. Do not fabricate a passing report in the publisher. |
| No exposed artifact link in an MR. | Check that the publisher exists in the MR pipeline, has `artifacts:expose_as`, and uploads the literal configured HTML path. Paths with CI variables/globs are unsuitable for this link. |
| The link opens an artifact browser rather than the report. | Publish exactly one HTML file in the exposing job's `artifacts:paths`. Retain JSON and other artifacts in the coverage job. |
| HTML downloads instead of opening in the browser, or preview is denied. | Check GitLab Pages availability and private/internal Pages access control. Download and open the standalone HTML locally if preview is unavailable. |
| An old pipeline's link no longer works. | Check artifact expiry/retention and job access. Download the same pipeline's report before expiry; do not mix old YAML with a new run's evidence. |

See the [GitLab publisher example](shard-verification.md#finding-a-class-assignment-in-gitlab) and
[report status reference](shard-verification.md#reading-html-statuses). ShardPilot does not change
project visibility, configure Pages or extend artifact retention.

## Maven native-runner checklist

| Symptom | Check / action |
| --- | --- |
| Adapter resolves through a Gradle composite but Maven cannot find it. | These are separate distributions. Install the root Maven reactor for development, or use a repository containing `shardpilot-parent`, `shardpilot-core` and `shardpilot-maven-plugin` at matching versions. |
| `Compiled test directory is missing`. | Run `test-compile` before explicit preparation/selection. Check the effective native `testClassesDirectory`; `shardpilot:verify` intentionally never compiles. |
| Native `excludesFile` wiring is rejected. | Both runner and adapter must reference the same generated path. Update native wiring when overriding `suite`, `directory` or `excludesFile`; keep maintained exclusions in `additionalExcludesFile`. |
| `-Dtest`, `-Dit.test`, a skip flag or filter-property override fails early. | These can bypass or invalidate class selection. Use the POM's supported native class filters, or an unsharded configuration without bound selection for focused runs. Count 1 still runs the adapter's validation. |
| A second Surefire execution is rejected. | Configure the implicit `default-test` execution rather than adding a second test binding. One active native goal execution per project is supported. |
| Failsafe selection fails before running tests. | Bind both native `integration-test` and `verify` goals, and bind adapter selection before the native integration runner. Use a separate integration output namespace. |
| Unit coverage exists but integration coverage is missing. | Explicit preparation/aggregation defaults to Surefire. Prepare and verify Failsafe separately with `-Dshardpilot.runner=FAILSAFE` and matching custom path/suite options. |
| `mvn verify` ran tests again in the aggregator. | Invoke `mvn shardpilot:verify` directly. The lifecycle and plugin goal are distinct; do not prepend `clean` to downloaded `target/` evidence. |
| Evidence from modules with identical class names collides. | Preserve each module's directory during collection. Reactor `-T` is not a class-shard index, and POM-only aggregators have no coverage output. |

The [Maven guide](maven.md) documents all parameters and supported native filters; the
[CI sequence](ci.md#maven-ci-sequence) shows the separate native-result and selection gates.
Native Maven fixtures currently cover JUnit Jupiter/Surefire/Failsafe 3.5.4; Gradle's Spock/TestNG
fixtures do not establish coverage of those frameworks through Maven.

## A useful bug report

Provide a minimal consumer build, build-tool/JDK/OS versions, DSL/POM, task or goal, index/count and sanitized
history that reproduces the issue. Include the baseline and per-shard outcomes when reporting
missing or duplicated tests. If possible, add a TestKit regression fixture. Do not upload private
test sources, secrets or production logs to a public issue. For Maven, include runner plugin versions,
the relevant effective plugin/execution configuration and module/suite paths; redact private repository
addresses and credentials. A native Invoker fixture is the Maven equivalent of a TestKit regression.
