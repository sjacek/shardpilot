# C1 duration-history comparison — 14 November 2026

**Outcome:** existing service scripts can be compared with published ShardPilot 0.5.0
without running producers. Matching outputs on partial service reports do not justify
automatic migration. The larger OPS archive exposes four policy differences requiring
review. Consumer pipelines and maintained timing TSVs remain unchanged.

## Implementations and scope

The `integration_test_durations.py` copies in the inspected SVC/OPS worktrees are identical
after line-ending normalization. The frozen copies used in the final comparisons have
SHA-256 `9a4324f30f7167f1d57c75e57998322c2f861a932d96b22a7f4b2324a4ac8b06`.
The root OPS checkout has no copy of that script, so its historical XML archive is compared
using the identical OPS-worktree script, not a purported script from that root revision.

The actual published `shardpilot-core:0.5.0` JAR is checksum-pinned to
`8526db32862e7287faa599e93ba3504a6743dad57cc59234ddd82b8241c32c97`, from the
previously verified registry audit. The comparison compiles only the small test-only
probe against that JAR; it does not substitute a Python rewrite of ShardPilot.

The source-derived baseline models legacy source availability, including helper classes.
It is **not native test discovery**. Options are floor 0.1 s, precision 1, FAIL duplicates,
failed suites excluded, minimum coverage 0.0, two diagnostic shards. SUM is recorded
separately. All XML was frozen before either algorithm read it; source contents are
hashed but not copied. Raw inputs/candidates remain in ignored private build output.

## Real XML results

| Separate input set | XML / reported tests | Python records | ShardPilot records | Result |
| --- | ---: | ---: | ---: | --- |
| SVC worktree `build/test-results/integrationTest` | 4 / 82 | 221 | 221 | MATCH; 4 measured, 217 retained, 8 old records dropped |
| OPS worktree `build/test-results/integrationTest` | 1 / 17 | 1 | 1 | MATCH; empty previous timing history, 59.7 s candidate |
| OPS root `build/verification/docker-api-full/junit` archive | 86 / 1,232 | 86 | 84 | REVIEW_REQUIRED; 4 record differences |

All three input sets report zero failures/errors/skips. This describes XML counters,
**not independently established successful CI runs**. SVC/OPS current sets are partial;
the archive has no verified producer revision/run/attempt association. Do not merge
these directories into one supposed nightly cohort. The archive comparison is an
explicit cold start, without importing its timings into maintained OPS history.

The initial SVC inspection contained two XML files; other local work added two before
the final freeze. The final result above deliberately identifies the four-file snapshot.
The earlier two-file comparison is retained separately and is not mixed into final XML.

The four archive deltas, identified by suite simple name:

| Suite | Python seconds | ShardPilot seconds | Explanation |
| --- | ---: | ---: | --- |
| `KeycloakOfficeIdentityTransportIT` | 0.9 | omitted | Two repeated testcase identities; two extra occurrences. |
| `OfficeAccessWorkflowIT` | 21.0 | omitted | Two repeated testcase identities; three extra occurrences. |
| `IntegrationTestImagesIT` | 0.0 | 0.1 | Positive minimum-duration floor. |
| `DockerApiTransportIT` | 0.0 | 0.1 | Positive minimum-duration floor. |

The first two suites are classified **ambiguous**, not proven retries or failed tests.
Repeated display names can be legitimate parameterized invocations. The timing policy
rejects their new measurements rather than silently sum uncertain identities; with a
valid previous history it would retain eligible older measurements. The cold-start
archive has none. Explicit SUM produces exactly the same four deltas because it only
changes duplicate-suite policy, not ambiguous testcase handling. Do not enable SUM
as a generic workaround. A consumer migration must first settle the native identity
and XML-dialect cases without weakening failure or coverage gates.

## Bounded synthetic controls

Sixteen real-implementation comparisons pass their expected outcomes: clean data,
rounding/floor, duplicate suites, repeated input roots, disjoint nested classes,
all-skipped suites, failed suites, retry markers, foreign classes, ambiguous identities,
overlapping nested reports, broken XML, internal DTD, non-finite timing, invalid previous
history and missing reports. This includes checks that rejected safe profiles never
produce a candidate and that method/output sentinels do not enter summary JSON.

Notable differences are Python binary/ties-to-even formatting (`1.25 → 1.2`) versus
ShardPilot HALF_UP (`1.25 → 1.3`), tiny `0.04 → 0.0` versus the 0.1 s floor, Python
duplicate sums versus default native rejection, and source-name records versus outer
class-family normalization. A repeated directory argument doubles the legacy measurement
while ShardPilot deduplicates the same path. Internal DTD is rejected by the native reader.

## Adapter and contract validation

Six isolated, offline runs exercise published **Gradle 9.9.1 / ShardPilot 0.5.0** and
**Maven 3.10.0 / ShardPilot 0.5.0** refresh entry points against each of the three frozen
input sets. TSV bytes and parsed native JSON match the core comparison in every case.
The seed's core/Gradle/Maven plugin JARs are rechecked against registry hashes. JDK 25
runs in a read-only container with private writable temporary/output directories and
network disabled. No application tests or test compilation are scheduled. This is a
refresh cross-check, not a benchmark or a new claim about application-suite success.

The [C2 design contract](../execution-evidence-contract.md) provides three Draft 2020-12
schemas, successful/missing-shard two-node examples and explicit semantic acceptance
requirements. AJV 8.20.0 validates all three schemas, five example documents, 25 invalid
shape controls and three deliberately schema-valid semantic-failure shapes. Four stdlib
fixture checks in CI keep byte hashes and cross-document examples consistent. These
checks do not implement or claim the future C2 validator.

## Review artifacts and adoption conditions

Private comparison directories contain `candidate.tsv`, `comparison.json`, native
`durations.json`, reference output, input/tooling hashes and frozen XML. Record-level
differences are under `profiles.safe.differences`; diagnostic SUM has a separate profile.
The comparison guide documents [commands and artifact meanings](../history-migration.md).

Before replacing service scripts: obtain a complete, independently identified producer
cohort and native outcomes; use its actual baseline/selection, decide skip/minimum coverage
policy and resolve ambiguous invocation reporting. Review candidates in a separate consumer
change. Retain blocking native/full tests. C2 implementation and adapters are the next
feature step; C3/C4 and the deferred dev benchmark are outside this comparison.
