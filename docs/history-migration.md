# Compare an existing duration-history script

The optional [offline comparison tool](https://github.com/sjacek/shardpilot/blob/main/scripts/compare_duration_history.py) runs an
existing Python history script and the **actual checksum-pinned released ShardPilot core**
on frozen inputs. It produces a candidate TSV, native diagnostics and a record-level JSON
diff. It never adopts history, runs application tests or changes a consumer pipeline.

The [14 November comparison report](reports/history-migration-2026-11-14.md) records the
service comparisons and the remaining migration conditions. The next step is the
[C2 execution-evidence contract](execution-evidence-contract.md), not automatic TSV adoption.

## Freeze inputs and choose an explicit policy

Use Python 3, a JDK with `java`/`javac` (probe bytecode targets Java 17), the consumer's
reviewed legacy script and a published stable core JAR. Obtain its SHA-256 independently
through the [published artifact verification](published-artifact-audit.md); computing a
checksum from an unreviewed download does not establish its authenticity.

```shell
python3 -B scripts/compare_duration_history.py \
  --legacy-script /consumer/scripts/ci/integration_test_durations.py \
  --sources /consumer/src/integrationTest \
  --previous /consumer/scripts/ci/integration-test-durations.tsv \
  --reports /collected/integration/shard-1 \
  --reports /collected/integration/shard-2 \
  --core-jar /verified/shardpilot-core-0.5.0.jar \
  --core-sha256 8526db32862e7287faa599e93ba3504a6743dad57cc59234ddd82b8241c32c97 \
  --output build/history-comparison/review-1 --shards 2 --diagnose-sum
```

Omit `--previous` only for an explicit cold start. A fresh output directory is mandatory.
The tool executes the supplied script: review it first and use a disposable environment,
network disabled and consumer inputs read-only. No Git credentials or secrets are needed.
Each subprocess has a 180-second timeout; the core JVM has a 256 MiB heap.

Discovery deliberately matches the old service script: **top-level `TEST-*.xml` in each
explicit root**. Nested roots must be listed separately. Files are copied once into a
private snapshot; repeated root arguments remain repeated so the comparison can expose
the legacy double-read versus ShardPilot's path deduplication. Reject symlinks and limit
reports to 64 MiB each, 256 MiB total and 20,000 files.

The snapshot retains XML bytes, previous TSV, legacy script, core JAR, comparison/probe
sources and their checksums. For source retention, it retains source **paths and hashes**,
plus empty availability placeholders, rather than copying private source contents.
`package-info` and `module-info` are not class candidates. Neither this inventory nor
its coverage ratio establishes native eligibility: helper classes may be present and
method/tag/group selection is unknown. The diagnostic baseline models source availability
only. Use a trusted native baseline before migration.

The comparison profile is fixed and explicit: source-derived family eligibility,
floor **0.1 s**, **one decimal place**, failed suites excluded, minimum coverage **0.0**,
duplicates **FAIL**. The zero coverage threshold permits a partial-data diagnostic;
it is not a recommendation for a production adoption gate. `--diagnose-sum` adds a
separate **SUM** profile without promoting its output to the canonical candidate.

## Inspect outputs

| File | Meaning |
| --- | --- |
| `inputs/manifest.json` | Hashes/lengths, report-root mapping, source inventory and frozen tooling identity. |
| `legacy/durations.tsv` | Actual old script's output, when successful. |
| `safe/durations.tsv` | Real-core FAIL-profile candidate, only when core refresh passes. |
| `safe/durations.json` | Core refresh diagnostics; invalid prior history produces a minimal `INVALID_HISTORY` status/reason document before refresh. |
| `sum-diagnostic/…` | Explicit SUM diagnostic; never adopted automatically. |
| `candidate.tsv` | Copy of the successful safe-profile output, even when differences require review. |
| `comparison.json` | Output hashes, record-level deltas, native rejection reason and adoption blockers. |

`MATCH` means both scripts succeeded and all parsed class/time records match. Comments
and TSV headers are intentionally not compared. `REVIEW_REQUIRED` also covers parser
rejection and invalid/non-finite legacy output. CLI exits are **0** for MATCH, **2** for
review required and nonzero for operational failure. A nonzero comparison is expected
for deliberate negative fixtures; do not hide a failure in a required CI gate.

Difference reasons include `DUPLICATE_SUITE`, `INPUT_ROOT_REPEATED`,
`ALL_SKIPPED_OR_EMPTY`, `FAILED_SUITE`, `RETRIED_SUITE`, `AMBIGUOUS_IDENTITY`,
`MINIMUM_FLOOR`, `FAMILY_NORMALIZATION`, `ELIGIBILITY_POLICY` and
`ROUNDING_OR_RETENTION`. These explain observed deltas, not a substitute for the native
refresh `reason`. When native refresh rejects the whole input, a missing candidate
does not mean the plugin assigned zero seconds to every legacy record.

Every comparison keeps `adoption.allowed=false`: local XML filenames and successful
counters cannot establish CI provenance/completeness. Review the record diff and require
an independently identified full cohort, native process outcomes, expected eligibility,
skip policy and consumer-owned coverage threshold before maintaining a new history.
Keep the whole output private: frozen XML and legacy logs may contain application data.
Public summaries contain only counts, hashes, class metadata and fixed reason codes.

## Reproduce policy and adapter checks

Fast orchestration tests run without the core or application sources:

```shell
python3 -B -m unittest discover -s scripts -p 'test_compare_duration_history.py' -v
```

Run the real implementations against 16 bounded synthetic profiles (a reviewed legacy
script is supplied externally; no private service script is vendored in this repository):

```shell
python3 -B testing/history-migration/verify.py \
  --legacy-script /consumer/scripts/ci/integration_test_durations.py \
  --core-jar /verified/shardpilot-core-0.5.0.jar \
  --core-sha256 8526db32862e7287faa599e93ba3504a6743dad57cc59234ddd82b8241c32c97 \
  --output build/history-comparison/policy-testdata
```

The [Gradle](https://github.com/sjacek/shardpilot/blob/main/testing/history-migration/adapters/gradle/build.gradle) and
[Maven](https://github.com/sjacek/shardpilot/blob/main/testing/history-migration/adapters/maven/pom.xml) fixtures additionally
cross-check the published adapter entry points. Copy each fixture to a disposable
directory, prepare dependencies separately, then run **offline**. Supply absolute paths:

```shell
export HISTORY_SNAPSHOT=/private/comparison/inputs
export HISTORY_BASELINE=/private/source-derived-baseline.yaml
export HISTORY_OUTPUT=/private/adapter-output
# In the Gradle fixture, using its pinned Gradle 9.9.1 Wrapper:
./gradlew --offline refreshIntegrationTestShardDurations -Pshardpilot.count=2
# In the Maven fixture, with Maven 3.10.0:
mvn -o io.github.sjacek:shardpilot-maven-plugin:0.5.0:refresh-durations
```

The diagnostic baseline is schema-version-1 manifest YAML with `classes` from the
snapshot's `eligible.txt`. It must not replace the consumer's real eligibility manifest.
Compare adapter TSV bytes and parsed diagnostic JSON against `safe/`. The Gradle fixture
rejects any task graph containing a `Test` or `JavaCompile` producer. Maven invokes the
standalone goal without lifecycle bindings. Its POM uses `<shardCount>2</shardCount>`;
the corresponding CLI property is `shardpilot.count`.

These checks establish refresh policy and adapter consistency on retained XML. They do
not measure CI speed, establish a complete application test run or validate C2 evidence.

## Import the Gradle fixture in IntelliJ IDEA

The Gradle adapter fixture is an independent build resolving published ShardPilot 0.5.0.
In IDEA, open `testing/history-migration/adapters/gradle/build.gradle` and select
**Link Gradle Project**, or use the Gradle tool window's link action to select that
directory. Select its checked-in Wrapper (Gradle 9.9.1 with distribution SHA-256) and a
supported JDK, then wait for synchronization to finish.
Importing only the repository root does not give this separate script its plugin classpath.

The typed `DurationHistoryRefresh` and `ShardHistoryOptions` imports are valid APIs from
the published plugin. Keep them and resolve the fixture's own Gradle model. Paths from
`HISTORY_SNAPSHOT`, `HISTORY_BASELINE` and `HISTORY_OUTPUT` are lazy providers: IDE import
and `gradle help` work before comparison inputs exist. Supply all three variables when
actually running refresh; missing inputs still fail. IDE linkage stays in local settings
and is not committed to the repository.
