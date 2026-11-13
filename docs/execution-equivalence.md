# Native execution equivalence

[Testing](testing.md) | [Class manifests](shard-verification.md) | [Review follow-up](security-review-2026-11-12.md)

These maintainer regressions compare actual native executions with ShardPilot disabled/absent
against the union of two and four shards of the same deterministic fixture. Class manifests and
the planner are not the oracle: matching class ownership alone cannot prove that methods,
parameter rows or repetitions executed with the same outcomes.

## Scenarios

| Adapter / native runner | Reference | Sharded cohorts |
| --- | --- | --- |
| Gradle / JUnit Jupiter | Native JUnit Platform task without applying ShardPilot | Two nodes, then four nodes |
| Gradle / Spock | Native JUnit Platform task without applying ShardPilot | Two nodes, then four nodes |
| Gradle / TestNG | Native TestNG task without applying ShardPilot | Two nodes, then four nodes |
| Maven / Surefire with Jupiter | Native `test`, no ShardPilot execution bound | Two nodes, then four nodes |
| Maven / Failsafe with Jupiter | Native `verify`, no ShardPilot execution bound; Surefire disabled | Two nodes, then four nodes |

Each scenario has four class families with equal maintained weights and 15 native test outcomes
in the reference, including skips. Parameter/data-provider inputs include a repeated value; repetitions, ordinary
successes, assertion failures, an uncaught test error and skipped tests are represented. Jupiter
also includes a nested class, a discovery-time disabled test and an aborted assumption. Spock
uses ignored features and runtime aborts; TestNG uses runtime `SkipException` cases and a successful
`@BeforeClass` fixture. This is deterministic execution coverage, not a performance benchmark.

Every class family deliberately contains an assertion failure. Native baseline and shard builds
must therefore fail through the normal test runner. TestKit checks the failed task against the
recorded outcomes; Invoker requires failure for all seven native commands. Neither harness enables
`ignoreFailures` / `maven.test.failure.ignore`. A configuration failure cannot substitute for the
expected test failures: listener evidence, known reference outcomes and the exact comparisons
must all be present.

Expected reference outcome counts:

| Framework | Passed | Assertion failure | Error | Aborted | Skipped |
| --- | --- | --- | --- | --- | --- |
| Jupiter and Spock | 8 | 4 | 1 | 1 | 1 |
| TestNG | 8 | 4 | 1 | 0 | 2 |

## Identity and comparison

`testing/execution-equivalence` contains shared native fixture sources. Jupiter and Spock use a
JUnit Platform `TestExecutionListener` registered through the normal service loader; TestNG uses
an `ITestListener` configured on its native Gradle runner. Listeners record terminal observations
as UTF-8 TSV, with one row per observation:

```text
v1<TAB>framework<TAB>TEST-or-CONTAINER<TAB>base64url-native-identity<TAB>outcome
```

Platform identities are engine unique IDs, including nested classes and parameter/repetition
indices. TestNG fixture identities include class, native method signature, fixed primitive/string
parameters and the native invocation index. Time, process IDs, display labels and report ordering
are not identity inputs. Comparisons are within one framework/runner; Jupiter IDs are not
normalized into TestNG IDs and Gradle IDs are not compared with Maven IDs.

JSON `baselineExecutions` / `shardExecutions` count terminal test observations, including skipped
tests, as native test-report totals do. The separate outcome counts distinguish skips from tests
that entered execution; a discovery-time disabled method is not claimed to have run its body.

Spock reports both a data feature and its iterations. The listener excludes a completed test node
that has child nodes, so the aggregate is not counted as another invocation. Failed non-test
containers are retained separately and compared, but do not inflate test invocation/outcome counts.
Skipped containers can likewise be retained as container evidence. The concrete fixtures above
exercise leaf-level skips and successful initialization, not every possible lifecycle failure.

The standalone, test-only `VerifyTestExecutions` oracle in `testing/java`:

- compares identity **and outcome** as a multiset, preserving repeated observations;
- rejects missing, extra or differently repeated observations even when distinct identity sets match;
- rejects one identity appearing on multiple shards, even when its overall multiplicity matches;
- distinguishes passed, assertion-failed, errored, aborted and skipped outcomes;
- rejects malformed or missing streams, empty references and aliased input/report files;
- writes deterministic JSON differences before returning failure, and removes an earlier report
  before validating a replacement cohort.

The oracle uses only JDK APIs. TestKit calls it directly; Invoker launches the same Java source
with its selected JDK, without publishing a test-support library or adding runtime plugin code.
Unit regressions mutate event streams to prove that equal aggregate totals or outcome totals
cannot conceal missing invocations, changed identities or swapped skip outcomes.

## Run and inspect

From the repository root:

```shell
./gradlew :shardpilot-gradle-plugin:test --tests '*ExecutionEquivalenceFunctionalTest' --tests '*VerifyTestExecutionsTest'
python3 -B scripts/maven_verification.py run -- -B -ntp verify '-Dinvoker.test=execution-equivalence-*'
```

On PowerShell use `gradlew.bat` and the installed Python executable. Both standard Gradle
`check assemble` and Maven `verify` include these regressions. The existing compatibility matrix
therefore runs them on its selected build-tool/JDK combinations without an additional optional gate.
Gradle fixture sources are declared test inputs, so edits invalidate earlier test results.
Dependency verification remains enabled; the launcher dependency is already in the reviewed
baselines and in TestKit cache warmup.

### IDE source model

The Gradle adapter models the shared sources as three separate source sets:
`executionJunitFixture`, `executionSpockFixture` and `executionTestNgFixture`. Reload the root
Gradle project after adding them. Each source set has its own framework dependencies; the JUnit
listener and its service descriptor belong to the same source set. This gives the IDE real
package roots and service-provider types without mixing the identically named JUnit/TestNG classes.

The adapter's `test` task compiles these source sets, but does not execute them directly. Their
intentional failures must only run inside the native comparison harnesses. To check compilation alone:

```shell
./gradlew :shardpilot-gradle-plugin:executionJunitFixtureClasses :shardpilot-gradle-plugin:executionSpockFixtureClasses :shardpilot-gradle-plugin:executionTestNgFixtureClasses
```

The four Spock fixture classes narrowly suppress `GrMethodMayBeStatic`: their feature methods
must remain instance methods. Native baseline and shard runs verify that they are discovered with
the expected outcomes. This does not disable package, dependency or control-flow inspections.
Command-line compilation and execution do not establish a clean IntelliJ batch/pre-commit inspection.

### Retained evidence

- Gradle: `shardpilot-gradle-plugin/build/reports/execution-equivalence/<framework>/`, containing
  original baseline/node TSVs, native XML, build logs and `two-shards.json` / `four-shards.json`.
- Maven: `shardpilot-maven-plugin/target/it/execution-equivalence-<runner>/target/executions/`,
  containing original baseline/node TSV/XML, Failsafe summaries and `2-shards.json` / `4-shards.json`.
  Invoker's native command logs remain alongside the cloned fixture.

The existing Gradle/Maven report artifacts retain this evidence for 14 days, including after
failure. A failing fixture is a maintainer regression failure; its deliberately failing native
tests are expected inputs, not a release plugin feature or permission to ignore consumer test failures.

## Local validation on 12 November 2026

Windows / Temurin 21.0.13, with dependency verification enabled and existing download caches:

| Check | Result |
| --- | --- |
| Gradle 9.9.1 `check assemble` | 344 tests passed (165 core and 179 adapter/tooling); no failures, errors or skips. Full run: 17m 41s. |
| Maven 3.10.0 `verify` | 302 unit tests passed (the same 165 core plus 137 adapter cases), all 17 Invoker fixtures and two runnable examples passed. Full run: 19m 40s. |
| Independent evidence inspection | All ten two/four-node comparison JSON reports passed; a separate multiset comparison of the original TSV streams matched each native reference exactly. |
| CI configuration | Actionlint accepted all three workflows; dependency policy verification passed with unchanged reviewed checksums and Actions pins. |

The adapter suite includes three framework executions of the new TestKit regression and nine
negative/positive oracle unit cases. The full native Maven run includes both new runner fixtures.
Logs and a combined local summary are under ignored `build/`: `execution-equivalence-gradle-full.log`,
`execution-equivalence-maven-full.log` and `execution-equivalence-validation.json`.
These durations describe concurrent local verification, not a sharding speedup measurement.
The subsequent [hosted run for commit `81e6827`](https://github.com/sjacek/shardpilot/actions/runs/43297476822)
passed all 15 jobs in 11m 43s: eight Gradle/JDK cells and three Maven/JDK cells, dependency warmup,
two runtime-security scans and the signed Maven bundle check. Downloaded artifacts independently
confirmed all 60 two/four-node comparisons across that matrix. This result belongs to that exact
revision; later published-artifact audit tooling has its own local evidence.

## Scope

The fixtures cover duration-weighted, class-family sharding of these deterministic suites. They
do not establish execution equivalence for every consuming application, retry policy, dynamic
test factory, random/environment-dependent identity, parallel data provider or lifecycle failure.
Maven Spock/TestNG execution is not covered by these new fixtures. Existing hash-fallback and
explicit-selection tests remain separate from this execution-equivalence evidence.

This change does not certify previously published registry artifacts or replace the planned
isolated network checks and real-suite benchmark. Hosted matrix results must be associated with
the exact revision being assessed; local runs do not establish a successful GitHub Actions run.
