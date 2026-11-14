# C2: execution evidence contract

**Design contract, 14 November 2026. Not implemented or available in ShardPilot 0.5.0.**
This specifies the next opt-in Gradle/Maven feature. It does not change the existing
`verify`/manifest or history-refresh contracts. The [C1 comparison](history-migration.md)
shows why matching duration histories cannot establish that a test cohort completed.

## Three documents and a trust boundary

The proposed wire format is UTF-8 JSON, schema version 1. Unknown fields and duplicate
JSON keys are rejected; incompatible changes require a new version. JSON Schema Draft
2020-12 describes shape, not semantic acceptance:

| Document | Author | Schema |
| --- | --- | --- |
| Expected cohort | Trusted aggregating CI job, using CI metadata and reviewed selection/plans | [expected-cohort-v1](schemas/expected-cohort-v1.schema.json) |
| Per-node evidence | Producer wrapper that observes the actual native test process | [execution-evidence-v1](schemas/execution-evidence-v1.schema.json) |
| Aggregate result | Future offline validator | [execution-report-v1](schemas/execution-report-v1.schema.json) |

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
plan SHA-256. The validator must reject duplicate indices/job IDs, holes and extra
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

Suggested v1 hard limits are 1 MiB per JSON document, 1,024 nodes, 20,000 XML files,
64 MiB per XML, 256 MiB total XML per node, depth 64 and 2,000,000 testcase elements
per node. Consumer configuration may lower limits; exceeding a limit fails without
parsing the remainder. A future adapter must expose the effective limits in its
configuration and validation evidence. This is not an unbounded XML file walker.

## XML interpretation and coverage

Reuse or extend the existing safe JUnit reader with dedicated regression tests;
do not silently change history timing semantics or copy an independent unsafe parser.
Reject DTDs, external entities, malformed/truncated XML, non-finite/negative counters,
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
names. Do not deduplicate by `(classname, name)` alone or manufacture identities from
XML row positions. The C1 archival example exhibits this ambiguity. Before C2 ships,
the dialect fixtures must establish when XML alone suffices and when a separately
specified native invocation ledger is required. No ledger format is introduced here.
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
semantic checks in the future validator. Syntax-valid evidence can describe a failed
process and must never itself imply successful execution.

## Adapter acceptance criteria for the next implementation

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

The current change delivers schemas, examples and this contract only. Implementing
that validator, JSON/HTML rendering and adapter wiring is a separate C2 step.

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

That optional development check compiles the three schemas, validates the five authored
documents and rejects 25 invalid shapes. Three schema-valid shapes deliberately describe
semantic failures (native exit 137, a different attempt, an index beyond the expected
count). They illustrate why the future semantic validator is still necessary.
