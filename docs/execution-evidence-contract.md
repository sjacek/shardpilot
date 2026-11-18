# C2: execution evidence contract

**Available in [ShardPilot 0.6.0](releases/0.6.0.md).**

The shared core validator powers the opt-in Gradle task and Maven goal described below. It does not change the existing
`verify`/manifest or history-refresh contracts. The [C1 comparison](history-migration.md)
shows why matching duration histories cannot establish that a test cohort completed.

For automatic profiles, the opt-in [selection-to-evidence bridge](execution-selection.md) adds a canonical
selection v2 that binds the C5 decision, per-node plans and optional C3 inputs. Expected requests and node
documents remain v1; aggregate report v2 adds validated provenance. Legacy v1 selection/report behavior is unchanged.

## Three documents and a trust boundary

The wire format is UTF-8 JSON, schema version 1. Unknown fields and duplicate
JSON keys are rejected; incompatible changes require a new version. JSON Schema Draft
2020-12 describes shape, not semantic acceptance:

| Document | Author | Schema |
| --- | --- | --- |
| Expected cohort | Trusted aggregating CI job, using CI metadata and reviewed selection/plans | [expected-cohort-v1](schemas/expected-cohort-v1.schema.json) |
| Per-node evidence | Producer wrapper that observes the actual native test process | [execution-evidence-v1](schemas/execution-evidence-v1.schema.json) |
| Aggregate result | Shared offline core validator | [execution-report-v1](schemas/execution-report-v1.schema.json) |

The expected request is supplied **separately** from downloaded producer artifacts.
Never construct it by copying a producer's revision, job IDs, selection hash or shard
count. CI authenticates the artifact/job association; hashes detect changed bytes,
but are neither signatures nor proof against a compromised producer. A retry has a
new attempt identity and a fresh artifact directory. Never mix attempts even if all
test counters happen to match.

Each node owns a distinct directory containing `evidence.json`, its existing
`plan.yaml` manifest and its declared report paths. CI also provides the reviewed
baseline and selection input separately. Native XML stays private. The public
[two-node example](examples/execution-evidence-testdata/expected.json) uses synthetic
metadata only; it is not evidence from an application or a CI run.

## Identity and selection

`cohort` binds exact Git revision (full SHA-1 or SHA-256), CI run ID, positive attempt,
project identifier, suite namespace, build tool and native runner. Gradle uses
`gradle-test`; Maven explicitly separates `surefire` and `failsafe`. Do not combine
unit/integration reports or projects merely because they use the same framework.
Indices are one-based and must be within the common shard count.

The expected request lists **every** node exactly once, its CI job ID and trusted
plan SHA-256. Each expected node also supplies `jobStartedAt` and `jobFinishedAt` from
trusted CI metadata, in UTC. These bound producer process times; they must not be copied
from the producer's process document. The validator rejects duplicate indices/job IDs, holes and extra
nodes; array `uniqueItems` alone cannot enforce this. Plan manifests retain their
current schema and class-family ownership rules. Their union must exactly cover
the independently supplied eligible selection, without cross-node ownership.

Hash inputs are exact bytes, including line endings:

- `eligibilitySha256`: the separately retained baseline manifest.
- `planSha256`: the separately retained per-node manifest.
- Selection `sha256`: canonical UTF-8 JSON, compact separators and sorted keys,
  `{"mode":"full","schemaVersion":1}` for full selection; for an explicit subset,
  also include `"families":[...]`, sorted unique binary outer class names. No trailing
  newline. Normalize a nested binary name to its outer family before canonicalizing.

These file-byte digests are distinct from the existing class-set checksums in manifest
coverage reports. Do not substitute a canonical class-name digest for a retained file hash.

The expected job verifies those bytes and semantic plan coverage itself. Source
file existence and producer-supplied counts do not replace native eligibility.
An explicit subset is never presented as a completed full-suite gate.

## Native process and retained reports

`process.state` distinguishes completion from timeout, cancellation, termination,
not-started and unknown. Only `completed` with exit code **0** can pass. A missing
wrapper result after a hard kill remains missing/incomplete; CI supplies that
failure, rather than inventing a successful producer document. Timestamps are UTC
RFC 3339, ordered and bounded by the job lifetime; clocks are diagnostic, not identity.
Retain the signed 32-bit JVM process exit value, including Windows failure values;
do not truncate it to an unsigned Unix-style byte. Unknown status is `null`.

The wrapper records the actual test command's exit status, before any artifact
collection. Shell pipelines must preserve it. Neither `after_script`, `always()`,
JUnit publication nor a successful aggregation command overrides a failed native
process. Successful XML with exit 137 still fails. Exit 137 alone does not prove OOM.

Each report descriptor binds a normalized relative POSIX `TEST-*.xml` path, length
and SHA-256. Resolve under that node's extraction root, reject symlinks, traversal,
absolute paths, aliases, duplicate paths and undeclared XML. Validate archive paths
**before extraction**. Read exact retained bytes; no reruns, discovery, downloads,
compilation or reconstruction of absent reports. Hash checks precede parsing.

Enforced v1 hard limits are 1 MiB per JSON/manifest document, 1,024 nodes, 20,000 XML files per node,
64 MiB per XML, 256 MiB total XML per node, depth 64 and 2,000,000 testcase elements
per node. Consumer configuration may lower limits; exceeding a limit fails without
parsing the remainder. Both adapters expose all eight limits, recorded in the aggregate
`limits` object. Directory scans also bound the number of entries (four times `maxReports`
plus 32 per node). Where filesystem file keys are unavailable, alias comparisons have
a separate bounded work budget; exhausting it fails with `RESOURCE_LIMIT`.

## XML interpretation and coverage

The execution view reuses the existing JUnit reader's hardened XML factory; history
timing semantics remain unchanged. It rejects DTDs, external entities, malformed/truncated XML, non-finite/negative counters,
counter inconsistencies and unknown report dialects. Disable external schema/network
access. Read only suite/testcase metadata needed for coverage and counters. Do not
include `system-out`, `system-err`, failure messages, method names, parameter values,
environment, arbitrary command lines, tokens or XML fragments in JSON/HTML/errors.

Gradle, Surefire and Failsafe need separate dialect fixtures. Nested classes map to
binary outer families. Inclusive parent summaries and separate nested reports must
not double-count a testcase. Cross-node reported families must belong to that node's
trusted plan. A missing planned family fails; an all-skipped family is reported but
does not count as executed. Unknown or ambiguous identities fail explicitly instead
of being treated as a duplicate successful execution.

Spock iterations and TestNG data-provider invocations may legitimately share display
names. The validator never deduplicates by `(classname, name)` or manufactures identities
from XML row positions. Repeated XML identities require a retained native invocation ledger,
described below. Distinct display names are accepted without a ledger. Retry markers,
duplicate suites and overlapping inclusive summaries remain unsupported and fail explicitly.
C2 does not claim method-level equivalence to an independent full run merely from
matching counters or class coverage; that remains the separate native-equivalence gate.

Producer `observed` counters are untrusted cross-checks; the validator recomputes
them and rejects disagreement. `null` means unavailable, never zero. `tests` includes
skips and `executed = tests - skipped`; failures/errors are disjoint terminal outcomes
within executed tests. Retry/overlapping summaries must first be resolved by the
tested dialect contract or rejected. XML evidence of failures/errors fails even if
the native process erroneously returned zero.

## Independent gates and result

The aggregate JSON has five independent gates:

1. **Provenance**: expected cohort, job association, hashes, paths and node completeness.
2. **Native process**: every required node completed with exit zero.
3. **Planned coverage**: the retained manifests cover the expected selection exactly.
4. **Reported coverage**: native reports belong to those plans, account for planned
   families and meet `minimumExecutedTests`, with consistent counters and no failures.
5. **Skip policy**: `reject` means no skipped test; `allow` still enforces an optional
   `maximumSkippedTests`. Skips remain visible in either case.

`allowEmptyShards` must be explicit. An empty node passes only when its trusted plan
is empty, it completed successfully, no undeclared report exists and that policy
allows it. An unexpected empty node fails; global minimum execution still applies.

`PASSED` requires all five gates and every node to pass. `UNKNOWN` in a gate means
aggregate `FAILED`, not a warning. On corrupt/incomplete input, counts may be `null`;
do not publish partial totals as complete. Node and aggregate `reasons` use the
bounded enum in the report schema. Errors explain the reason code and safe shard
index, not the rejected payload. HTML must escape all metadata and display the
same gates and skip counts as JSON. A deterministic result orders nodes by index
and reasons lexicographically, with no current timestamp in the logical result.

Schema validation cannot check cross-document identities, hashes, uniqueness by
index, counts, XML, manifest coverage or all-gates-pass logic. Those are mandatory
semantic checks in the core validator. Syntax-valid evidence can describe a failed
process and must never itself imply successful execution.

## Adapter acceptance criteria

Both adapters must delegate to one portable core validator with no service-specific
classes. Register validation/report tasks or standalone Maven goals only after explicit
opt-in. Supply expected values as declared inputs; separate outputs from plans, raw
XML and maintained timing history. No dependencies on test/compile/prepare tasks,
no automatic adoption and no weakening of the consumer's existing native/full gates.

Required negative controls include stale revision, wrong run/attempt/project/suite,
wrong tool/runner, changed selection or plans, missing/duplicate/foreign nodes,
changed/truncated/undeclared/symlinked XML, DTD/entity payloads, inconsistent counters,
native nonzero/unknown/timeout, missing planned families, overlapping nested reports,
ambiguous repeated identities and skip/minimum-execution policy violations. Positive
fixtures must cover Gradle Jupiter/Spock/TestNG and Maven Surefire/Failsafe, including
allowed empty nodes and legitimate repeated invocations. Verify that every failure
keeps native CI gates blocking and that output contains no private payloads.

The shared validator, JSON/HTML rendering and both adapter entry points are implemented
in published 0.6.0. Native execution-equivalence tests remain a separate gate.

## Native invocation ledger

An optional evidence field `invocations` is a `{path, sha256, bytes}` descriptor for
a bounded JSON file following [invocation-ledger-v1](schemas/invocation-ledger-v1.schema.json).
The file contains `schemaVersion: 1`, a native `runner` (`junit-platform` or `testng`)
and `invocations`, each with `id`, `className` and `outcome` (`passed`, `failed`, `error`,
`skipped`). IDs are SHA-256 digests of native engine identities captured by a producer
listener. Hash them before retention: do not retain raw method names or argument values.

The validator requires unique native IDs across the whole cohort and compares per-binary-class
outcome multiplicities with the original XML. A ledger does not erase skips or failures and
does not authorize overlapping reports or retries. Changing its bytes fails the digest gate.
XML alone can establish unambiguous class coverage/counters; it does not establish independent
method-level equivalence. A producer remains inside the CI trust boundary in either mode.

## Gradle configuration

Configure this once outside a deferred `TaskProvider.configure` action:

```groovy
tasks.named('integrationTest', Test).get().shardPilot.executionEvidence {
    expectedRequest = layout.projectDirectory.file('trusted/expected.json')
    eligibilityFile = layout.projectDirectory.file('trusted/baseline.yaml')
    selectionFile = layout.projectDirectory.file('trusted/selection.json')
    nodesDirectory = layout.projectDirectory.dir('downloaded')
    // Optional: lower any distribution ceiling.
    maxReports = 5000
}
```

Run `./gradlew --offline verifyIntegrationTestExecutionEvidence`. Default outputs are
`build/shardpilot/integrationTest/execution-evidence.json` and `.html`, or the corresponding
selected-suite namespace. Override `reportFile`/`htmlReportFile` independently. An aggregation-only
project can register the public `VerifyExecutionEvidence` task type directly, supplying those
six paths. No Java plugin is required for that standalone task.

Kotlin DSL uses the same managed properties:

```kotlin
tasks.named<Test>("integrationTest").get().extensions
    .getByType<io.github.sjacek.shardpilot.ShardPilotExtension>().executionEvidence {
        expectedRequest.set(layout.projectDirectory.file("trusted/expected.json"))
        eligibilityFile.set(layout.projectDirectory.file("trusted/baseline.yaml"))
        selectionFile.set(layout.projectDirectory.file("trusted/selection.json"))
        nodesDirectory.set(layout.projectDirectory.dir("downloaded"))
    }
```

## Maven configuration

Invoke the standalone `verify-executions` goal on the development build of the Maven plugin:

```shell
mvn -o shardpilot:verify-executions \
  -Dshardpilot.executionExpectedRequest=trusted/expected.json \
  -Dshardpilot.executionEligibilityFile=trusted/baseline.yaml \
  -Dshardpilot.executionSelectionFile=trusted/selection.json \
  -Dshardpilot.executionNodesDirectory=downloaded
```

POM configuration names are `expectedRequest`, `eligibilityFile`, `selectionFile`,
`nodesDirectory`, `reportFile` and `htmlReportFile`. The output properties are
`shardpilot.executionReportFile`/`executionHtmlReportFile`; defaults are
`target/shardpilot/execution-evidence.json` and `.html`. For multiple suites, use a
separate trusted request, node directory and output pair per invocation. There is no
default lifecycle phase or dependency resolution. Without a project, invoke the fully
qualified development plugin coordinate and supply both output paths explicitly.

| Gradle task property | Maven property (prefix `shardpilot.execution`) | Ceiling |
| --- | --- | ---: |
| `jsonBytes` | `JsonBytes` | 1048576 |
| `manifestBytes` | `ManifestBytes` | 1048576 |
| `maxNodes` | `MaxNodes` | 1024 |
| `maxReports` | `MaxReports` | 20000 |
| `maxReportBytes` | `MaxReportBytes` | 67108864 |
| `maxTotalReportBytes` | `MaxTotalReportBytes` | 268435456 |
| `maxDepth` | `MaxDepth` | 64 |
| `maxTestCases` | `MaxTestCases` | 2000000 |

## Collection and failure handling

Both entry points inspect an already extracted, read-only directory with exactly the
immediate `node-N` subdirectories. Each contains `evidence.json`, `plan.yaml`, declared
`TEST-*.xml` reports and its optional ledger. The verifier does not extract archives:
the CI collector must validate archive entries before extraction. Symlinks, special files,
hard-link aliases, undeclared native reports and unsafe relative paths fail validation.
Collect exactly the declared `TEST-*.xml` files: other XML files (including Maven's
`failsafe-summary.xml`) are not a supported C2 report and must stay outside node directories.

The producer wrapper must record the actual native command's process result. Neither adapter
invents a process result from successful XML, a successful artifact upload or a class-selection
manifest. Collect artifacts even after a native failure and retain each job's native gate.
Do not configure `ignoreFailures`, `testFailureIgnore` or a pipeline continuation that turns
native failures into successful CI gates. C2 adds a required aggregate gate, not a replacement.

Every verifier invocation rechecks bytes, including after Configuration Cache restoration.
No Test, JavaCompile, prepare/selection, Git or history-refresh dependency is added. Missing
inputs produce FAILED reports with unknown counts, replacing earlier successes. JSON and
HTML share one immutable evaluation and are deterministic for identical inputs. An unreadable
expected request has `cohort: null`; a missing request also has `expectedRequestSha256: null`.
Output/input aliases are rejected before any write. Unwritable outputs fail the adapter.

## Validate the authored examples

The stdlib CI checks validate references, example hashes and cross-document consistency:

```shell
python3 -B -m unittest discover -s testing/contracts -p 'test_*.py' -v
```

For full offline Draft 2020-12 validation, provide an existing separate AJV 8 and
`ajv-formats` installation, without adding them to either plugin's dependencies:

```shell
node testing/contracts/validate-schemas.cjs /path/to/validator-installation
```

That optional development check compiles the v1/v2 schemas, validates both authored cohorts
and rejects malformed shapes, including missing selection provenance and invalid plan digests. Three schema-valid shapes deliberately describe
semantic failures (native exit 137, a different attempt, an index beyond the expected
count). They illustrate why semantic validation is required in addition to schemas.
