# JUnit XML timing spike (F0.1–F0.4)

Measured on 2026-10-27 against ShardPilot `main` commit `6115990`.
Branch: `spike/junit-xml-maven-reruns`. No production code, public API, publication,
version or CI configuration changes are part of this spike.

**Gate result: F0.1–F0.4 complete; F1–F2 may start under the revised rules.**
The initial F0.3–F0.4 stop was correct: Maven reports an executed inclusive family
with `tests="0"`, and Gradle suite time includes retries. On 2026-10-27 the user
accepted revised P3–P5, testcase metadata inspection and family normalization.
The subsequent Gradle F0.1–F0.2 gate confirmed disjoint Jupiter family members,
one suite per executed Spock/TestNG class, and suite time including setup costs.
Production duration refresh is still unimplemented. F0.5/M5 and F0.6 remain open.

## Experiment and reproduction

The standalone fixtures comprise a [Gradle TestKit build](../../spikes/junit-xml-timings/gradle-testkit/build.gradle),
a [Maven Invoker project](../../spikes/junit-xml-timings/maven-invoker/pom.xml)
and a [metadata exporter](../../spikes/junit-xml-timings/summarize.py).
They are deliberately outside the production modules and the ordinary release builds.
Generated builds remain under ignored `target/` and `build/` directories.
TestKit materializes `*.template` files as sources in the generated fixture project;
Spock feature methods remain instance methods there.

| Component | Version / configuration |
|---|---|
| OS / JDK | Windows amd64 / Eclipse Temurin 25.0.4.1+1-LTS (Java sources target 17) |
| Maven / Invoker | 3.9.16 / 3.9.1 |
| Surefire / Failsafe | 3.5.4 / 3.5.4 |
| Jupiter | 6.1.3 |
| Gradle / TestKit | 9.8.1 / 9.8.1 |
| Gradle test retry | `org.gradle.test-retry` 1.6.6, `maxRetries=1`, default pass-after-retry policy |
| Spock / Groovy | 2.5-groovy-5.0 / 5.1.3 |
| TestNG | 7.12.0 |
| Parallelism | One Gradle test worker; default sequential Jupiter execution |

From the repository root, with `JAVA_HOME` pointing at the JDK:

```sh
./mvnw -B -ntp -f spikes/junit-xml-timings/maven-invoker/pom.xml verify
./gradlew -p spikes/junit-xml-timings/gradle-testkit test --console=plain
python -B spikes/junit-xml-timings/summarize.py --output build/spikes/junit-xml-timings.json
```

F0.1–F0.2 use the **existing** `ShardPilotFunctionalTest` Jupiter/Spock and TestNG
fixture builders, with controlled sleeps and phase logging added in two dedicated
test methods. The probe sources are generated inside `@TempDir`, not production
source sets. Before cleanup, their reports are retained under
`build/spikes/junit-xml-timings/{jupiter-spock,testng}`. Commands:

```sh
./gradlew :shardpilot-gradle-plugin:test --tests '*ShardPilotFunctionalTest.records*XmlTimingEvidence' --console=plain
python -B spikes/junit-xml-timings/summarize.py --include-gradle-gate --output build/spikes/junit-xml-complete.json
```

The exporter with `--include-gradle-gate` requires both earlier standalone builds
and the Gradle entry gate. It adds the selected phase body times, without exporting
raw build logs. Configuration cache was reused by the completed entry gate.

On Windows use `mvnw.cmd` and `gradlew.bat`. To repeat an already successful
TestKit measurement, add `--rerun-tasks`; each nested build performs `clean test`.
The Maven Invoker executions always run `clean verify` in separate clones:

- `maven-invoker/target/it/jupiter`: all fixtures, `rerunFailingTestsCount=1`.
- `maven-invoker/target/it-no-retry/jupiter`: only nested/disabled fixtures,
  `-Dspike.reruns=0 -Dtest=OuterTest,DisabledClassTest,DisabledMethodsTest`
  and the same `-Dit.test=...` selection for Failsafe.
- `gradle-testkit/build/probes/merge-false` and `merge-true`: independent TestKit
  builds with `reports.junitXml.mergeReruns=false` / `true`.

`FlakyTest` persists its attempt count to a runner-specific file: retries may start
another JVM, so a static Java counter would not be reliable. Its first attempt sleeps
700 ms and fails; the second sleeps 200 ms and passes. Attempt body times are recorded
independently of XML. `OuterTest.outer` sleeps 200 ms and `OuterTest$Inner.nested`
sleeps 700 ms. These contrasting durations distinguish inclusive time from exclusive
time without relying on equality at millisecond precision.

The Maven hook verifies successful retries in both runners and that the nested test
actually ran, without assuming it has its own XML file. The TestKit assertions verify
both successful retry builds, two attempts, and reports for all disabled fixtures.
Raw XML can contain environment properties and failure output. The summary exporter
copies only allowlisted counters, testcase identities and timings; no properties,
hostnames, absolute paths, system output or error messages. It is an experiment
utility, **not** the production XML reader.

## Observations

Every observed report has a `testsuite` root. No `testsuites` root was produced;
support for that alternative still needs synthetic parser tests in F1.

All numbers below are one completed measurement series, in seconds. They describe
these exact framework/runner versions, not a claim about every JUnit XML producer.

| Framework / runner | Report files and suite identity | Nested timing | Retry representation | Fully skipped class |
|---|---|---|---|---|
| Jupiter / Surefire 3.5.4 | `target/surefire-reports/TEST-example.<Class>.xml`; nested test appears in `TEST-example.OuterTest.xml` | Enclosing `time` includes outer **and** nested work; no separate `OuterTest$Inner` report | One suite and one testcase; `flakyFailure` is a **child of testcase**; header failures/errors are 0 | Both class-level and method-level `@Disabled`: tests=2, skipped=2; positive suite time possible |
| Jupiter / Failsafe 3.5.4 | `target/failsafe-reports/TEST-example.<Class>.xml`; same basenames as Surefire | Same inclusive behavior, also without retries | One suite and one testcase with `flakyFailure`; header failures/errors are 0 | tests=2, skipped=2; positive suite time possible |
| Jupiter / Gradle, merge=false | `build/test-results/test/TEST-example.<Class>.xml` | Not exercised by F0.4 | One suite, **two testcases with the same identity**; first has `failure`, second passes; suite failures=1 | Class- and method-level `@Disabled`: tests=2, skipped=2 |
| Jupiter / Gradle, merge=true | Same paths and one suite per class | Not exercised by F0.4 | One suite, one merged testcase with `flakyFailure`; **suite failures still 1** | tests=2, skipped=2 |
| Spock / Gradle, both merge settings | `TEST-example.IgnoredSpec.xml` | Not exercised; only `@Ignore` behavior tested | No retry for the ignored spec | Class-level `@Ignore`: tests=2, skipped=2 |
| Jupiter / Gradle, F0.1 | `TEST-example.AlphaTest.xml` and `TEST-example.AlphaTest$Nested.xml` | Separate, disjoint suite times; outer does not include nested | No retry configured | Covered separately above |
| Spock / Gradle, F0.2 | One `TEST-example.EchoSpec.xml` for three unrolled data rows | One class, three distinct testcase identities; suite includes setupSpec | No retry configured | Covered separately above |
| TestNG / Gradle, F0.2 | One `TEST-example.AlphaTest.xml` for ordinary + two data-provider invocations | One suite includes BeforeClass; no duplicate class suites | No retry configured | Not exercised for TestNG |

### Maven nested suites and the zero-test counter

Tuple notation: `(tests, skipped, failures, errors)`.

| Runner | Rerun count | Outer suite counters | Outer suite time | Sum of testcase times | Testcase classnames |
|---|---:|---|---:|---:|---|
| Surefire | 1 | (0, 0, 0, 0) | 0.912 | 0.908 | `example.OuterTest`, `example.OuterTest$Inner` |
| Failsafe | 1 | (0, 0, 0, 0) | 0.908 | 0.906 | Same |
| Surefire | 0 | (0, 0, 0, 0) | 0.925 | 0.920 | Same |
| Failsafe | 0 | (0, 0, 0, 0) | 0.941 | 0.927 | Same |

Both testcases passed. The outer suite's approximately 0.9 s cannot be the outer
method's approximately 0.2 s alone; it includes the nested method. The control run
shows that this layout and `tests="0"` are not caused by enabling retries.
There is one outer family report per runner, not two inclusive/exclusive suites.

Consequences:

- `tests == 0` is **not** sufficient evidence that no tests ran in this producer/version.
- `skipped == tests` also holds for this 0/0 header, despite both testcases executing.
- Suite attributes alone do not identify the nested member, although the suite time
  can represent the whole outer family. A generic importer must not also add a
  separate nested measurement to an already inclusive family duration.

### Retry durations and counters

All four retry builds returned success. Each attempt file contains exactly attempts
1 and 2. The first body is approximately 0.7 s; the successful body approximately
0.2 s.

| Runner / reporting | Suite counters | Suite time | Sum of emitted testcase times | Visible failed-attempt marker |
|---|---|---:|---:|---|
| Surefire | (1, 0, 0, 0) | 0.204 | 0.204 | `testcase/flakyFailure` |
| Failsafe | (1, 0, 0, 0) | 0.206 | 0.204 | `testcase/flakyFailure` |
| Gradle merge=false | (2, 0, 1, 0) | 1.762 | 0.940 | First `testcase/failure` |
| Gradle merge=true | (2, 0, 1, 0) | 1.746 | 0.217 | Merged `testcase/flakyFailure` |

For Maven, suite time describes the successful rerun and does not include the
approximately 0.7 s failed body. For Gradle, suite time spans the failed and successful
attempts, including the elapsed interval between attempts. It is neither just the
successful testcase time nor exactly the sum of testcase times. Merging changes
testcase representation, but did **not** normalize the suite's `tests`/`failures`
counters in this measurement.

No retry created a second suite or second report for the same class **inside a
single runner/report directory**. Therefore, retry alone did not trigger the
suite-level duplicate policy P5 in these fixtures. Two same-named testcase nodes
are not two class/suite measurements.

Running the same classes in Surefire and Failsafe intentionally creates duplicate
suite names in **different directories**. Combining those directories would trigger
`FAIL`, even though each runner succeeded. F3 must select the intended runner's
reports rather than implicitly importing both.

### Disabled and ignored classes

| Runner | Fixture | tests / skipped | Suite time |
|---|---|---|---|
| Surefire, retry enabled | Disabled class / disabled methods | 2 / 2 | 0.009 / 0.024 |
| Failsafe, retry enabled | Disabled class / disabled methods | 2 / 2 | 0.006 / 0.020 |
| Surefire, retry disabled | Disabled class / disabled methods | 2 / 2 | 0.017 / 0.021 |
| Failsafe, retry disabled | Disabled class / disabled methods | 2 / 2 | 0.006 / 0.022 |
| Gradle merge=false | Disabled class / disabled methods / ignored Spock spec | 2 / 2 | 0.000 / 0.013 / 0.002 |
| Gradle merge=true | Disabled class / disabled methods / ignored Spock spec | 2 / 2 | 0.001 / 0.015 / 0.005 |

Positive suite time does not prove useful test execution. For these genuinely
disabled/ignored fixtures, `tests > 0 && skipped == tests` reliably identifies
the absence of a usable timing sample. This does not rescue the zero-test Maven
nested header described above.

## Gradle entry gate: Jupiter, Spock and TestNG (F0.1–F0.2)

The two probe tests passed on 2026-10-27. Each fixture uses the existing
ShardPilot plugin classpath and configuration, one worker, and no retry plugin.

| Fixture / suite | tests / skipped / failures / errors | Suite time | Sum of testcase times | Independently measured body time |
|---|---|---:|---:|---|
| Jupiter outer `example.AlphaTest` | 1 / 0 / 0 / 0 | 0.219 | 0.211 | outer 0.201 |
| Jupiter nested `example.AlphaTest$Nested` | 1 / 0 / 0 / 0 | 0.703 | 0.703 | nested 0.701 |
| Spock table `example.EchoSpec` | 3 / 0 / 0 / 0 | 1.044 | 0.325 | setupSpec 0.531; three row bodies total 0.303 |
| TestNG data provider `example.AlphaTest` | 3 / 0 / 0 / 0 | 1.033 | 0.406 | BeforeClass 0.601; three test bodies total 0.401 |

All nine entry-gate XML files have a `testsuite` root. Each suite has the expected
binary name and one file; data rows have different testcase names. Jupiter's outer
time is approximately its 200 ms method and does not contain the nested 700 ms
method. Adding the two suite times gives a family sample of **0.922 s**, before
the refresh task's future rounding/minimum. There is no inclusive overlap here.

Spock's three names are `row 1`, `row 2`, `row 3`. TestNG reports `ordinary`,
`row[0](first)` and `row[1](second)`. Thus the approved ambiguity check does not
mistake these data-driven invocations for repeated identities. Suite-minus-testcase
time is about 0.719 s for Spock and 0.627 s for TestNG; independent phase logs show
that setup contributes to those differences. The differences also include framework
overhead, so they must not be interpreted as exact setup duration alone.

The gate confirms using **suite time**, including setup cost, rather than replacing
it with testcase sums. Testcase time was inspected only for this measurement; the
production design needs identities/flags, not testcase durations for reconstruction.

## Initial assessment before the correction was approved

Reference: workspace analysis
`wiki/docs/architecture/background/analysis-shardpilot-duration-history.md`, §4.1,
§5 P3–P5 and §6. This section records the initial assessment; corrections were
not applied unilaterally. The user's subsequent approval is recorded below.

| Rule / premise | F0 assessment | Consequence / proposal for decision |
|---|---|---|
| Passed suite replaces previous measurement | **Requires refinement** | "Passed" and duration semantics differ across retry producers. Decide whether history means a clean execution, final successful attempt or total retry cost. |
| All skipped or `tests == 0` means no measurement (P3) | **Requires change** | Keep ignoring proven all-skipped suites, but do not silently discard the observed executed Maven family on its zero counter. Decide on a metadata fallback or explicit rejection of this unsupported/inconsistent report shape. |
| A failed suite is excluded unless explicitly included (P4) | **Confirmed as a conservative header-based filter**, with an important limitation | Gradle's green retry still has failures=1 and is excluded by default. Maven's green retry has failures=0 and is accepted with only the final attempt time. Document/decide whether that asymmetry is acceptable. |
| Clamp small accepted timings to `minimumSeconds` | No contrary evidence; arithmetic not tested in F0 | Still needs F1 unit tests; an ignored suite must not be turned into a 0.1 s measurement. |
| Duplicate suite name is an error; optional SUM (P5) | **Confirmed for the tested single-runner layouts** | Retry creates testcase repetition/merging, not duplicate suites. Keep FAIL; require deliberate runner-directory selection. Do not infer that all frameworks/retry combinations behave this way. |
| Nested suite is a separate exclusive record | **Requires change / producer-specific normalization** | Maven emits one inclusive outer report here. The subsequent F0.1 confirmed separate disjoint Gradle suites; summing inclusive outer time and child time would double count work. |
| Keep old unmeasured eligible records | Not exercised by this reporting spike | No revision indicated; verify in F1. |
| Drop old unmeasured ineligible records | Not exercised | No revision indicated; verify in F1. |
| Ignore measured foreign records and count them | Not exercised | No revision indicated; verify in F1. |
| Zero accepted measurements is an error | Not exercised as a refresh operation | Retain the error, but first resolve which nested/retry reports qualify. |

The distinction "not exercised" is intentional: this spike does not implement the
refresh function and cannot empirically confirm its merge/format/coverage rules.

## Approved correction and current gate outcome

The user accepted these corrections on 2026-10-27:

- P3: testcase skipped flags determine execution when testcase metadata exists;
  otherwise use header counters. An executed Maven family is not discarded because
  its suite counter is zero. Apply minimum once to an accepted family sample.
- P4: exclude retry markers and ambiguous repeated testcase identities, regardless
  of build success or `includeFailedSuites`. Preserve previous eligible history.
- P5: exact suite-name duplicates still fail before filtering, or are explicitly
  summed with flags preserved. Repeated testcase nodes are not duplicate suites.
- One new record per completely measured family. Sum disjoint suite times, count
  inclusive time once, reject ambiguous overlaps, and replace all previous members
  of the measured family together. Retain old eligible records without a complete
  new family measurement.
- The parser may inspect testcase identities and result markers, while still
  ignoring test output, messages and stack traces. The JSON report distinguishes
  retried/ambiguous exclusions and suite/family/old-record counts.

Canonical specification: workspace wiki
`docs/architecture/background/analysis-shardpilot-duration-history.md`, §4.1 and §5;
implementation prompt: `Claude outputs/shardpilot-prompts/02-shardpilot-f1-f2-core-gradle.md`.
Known Maven inclusive headers and retry behavior are now covered by the approved
rules. F0.1–F0.2 found no new unhandled overlap or duplicate suite behavior, so the
gate is **passed for the measured stack**. New incompatible observations would
require another report and decision; a full version compatibility matrix is not claimed.

## Validation and evidence

- Maven Invoker: **2/2 invocations passed**, each exercising both Surefire and
  Failsafe (four runner executions). The retry fixture deliberately emits initial
  failures, but both runners subsequently pass; the post-build hook verifies attempts.
- Standalone Gradle TestKit: **2/2 parameterized invocations passed** (merge=false/true).
- Existing ShardPilot TestKit fixture entry gate: **2/2 tests passed**; 9 XML files,
  9 Jupiter/Spock testcase executions and 4 TestNG testcase executions.
- Existing shard coverage regression tests: **4/4 parameterized invocations passed**
  (Jupiter/Spock and TestNG, each with and without duration history).
- Summary exporter: six variants, **22 XML files**; generated JSON contains only
  the selected timing metadata.
- The Maven and Gradle experiments were repeated in the final standalone fixture
  location; the nested and retry counter behavior was reproduced. Tables show the
  final series, not averages across runs.
- Combined exporter with the entry gate: **8 variants / 31 XML files**.
- Full root `check assemble` and Maven reactor `verify` were not run: production
  sources, dependencies and configuration are unchanged. Selected existing-root
  TestKit probes/regression tests above are the validation for this change;
  production F1–F2 verification is not claimed.

Regression command:

```sh
./gradlew :shardpilot-gradle-plugin:test --tests '*ShardPilotFunctionalTest.shardsCoverJupiterNestedAndSpockTestsWithoutDuplicates' --tests '*ShardPilotFunctionalTest.testNgDataProviderRowsStayWithTheirClassWithoutCoverageGaps' --console=plain
```

Local logs from this measurement are outside the repository under workspace
`output/shardpilot-f0-maven.log` and `output/shardpilot-f0-gradle.log`.
The allowlisted snapshot is `output/shardpilot-f0-timings.json`; it can be regenerated
at any destination using the exporter. Times will vary on subsequent runs.
Entry-gate and regression logs are `output/shardpilot-f0-gradle-gate.log` and
`output/shardpilot-f0-gradle-regression.log`; the combined allowlisted snapshot is
`output/shardpilot-f0-complete-timings.json`.

The observed retry node structures are consistent with the descriptions in
[Gradle's JUnit XML reporting documentation](https://docs.gradle.org/9.8.1/userguide/java_testing.html)
and [Surefire's rerun documentation](https://maven.apache.org/surefire/maven-surefire-plugin/examples/rerun-failing-tests.html).
Those descriptions do not replace the measured suite counters and timings above.

Suggested commit message:

```text
test: document JUnit XML nested and retry timing semantics

- Add standalone Maven Invoker and Gradle TestKit timing probes
- Measure disabled suites, nested families and successful retries
- Document F0 findings and blockers for duration history refresh
```
