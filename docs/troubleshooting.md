# Troubleshooting

[Documentation index](README.md)

Start with the task path, plugin revision, enabled flag, shard count/index and exact history
snapshot used by the job. Compare the same inputs across shards before changing test framework
configuration. Keep credentials, personal data and raw application output out of public bug reports.

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

## A useful bug report

Provide a minimal consumer build, Gradle/JDK/OS versions, DSL, task path, index/count and sanitized
history that reproduces the issue. Include the baseline and per-shard outcomes when reporting
missing or duplicated tests. If possible, add a TestKit regression fixture. Do not upload private
test sources, secrets or production logs to a public issue.
