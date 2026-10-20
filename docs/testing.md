# Testing and compatibility

[Documentation index](README.md)

Run checks from the ShardPilot repository, not a consuming application's build. The Wrapper pins
the Gradle version; use a JDK supported by that Gradle runtime.

## Local verification

```shell
./gradlew check assemble
```

PowerShell:

```powershell
.\gradlew.bat check assemble
```

`check` runs the test suite and Gradle plugin validation. `assemble` builds the plugin, sources and
Javadoc artifacts. Functional tests use Gradle TestKit and temporary consumer builds, so the first
run needs Maven Central access for fixture dependencies. Keep TestKit execution sequential: nested
Gradle daemons and workers consume additional memory beyond the outer test worker.

For a focused iteration:

```shell
./gradlew test --tests 'io.github.sjacek.shardpilot.ShardPlanTest'
./gradlew test --tests 'io.github.sjacek.shardpilot.ShardPilotFunctionalTest'
```

The plugin's own test task is not configured to shard itself. These selectors run normal Gradle
tests. Run the complete `check assemble` before handing off a change.

Standard outputs are `build/reports/tests/test/index.html`, `build/test-results/test/` and
`build/libs/`. TestKit fixtures retain strict configuration-cache settings so unsupported captures
fail instead of being reduced to warnings.

## Test responsibilities

| Test class | Behavior covered |
| --- | --- |
| `DurationHistoryTest` | Comments, blank lines, CRLF, nested names, immutable results, malformed metadata and safe error messages. |
| `ShardPlanTest` | Deterministic packing/ties, nested aggregation, floating-point order, hash fallback, complete ownership, invalid settings and overflow. |
| `ShardPilotFunctionalTest` | Real Gradle tasks with Jupiter/Spock/native TestNG, weighted/hash-only partitions, provider-row ownership, opt-in, filters, custom tasks, empty results, fatal setup failures, Kotlin DSL, actual Gradle/JDK runtimes and configuration-cache reuse. |
| `NormalizeSvgTest` | Standalone preview normalization, text/geometry/link preservation, XML input safety, idempotence and committed SVG compatibility checks. |
| `ReleaseTagVerificationTest` | Actual Gradle guard script, stable/malformed/missing/mismatched tags, evaluated build versions and strict configuration-cache reuse. |
| `PublicationWorkflowTest` | Parsed YAML, complete matrix dependency gating, immutable checkout SHA, required jobs, credential isolation and disabled publisher caches. |

Functional partition checks compare the union of shard results to an unsharded baseline and check
for duplicates. Configuration-cache checks reuse a build, then change timing contents to verify
that old test results are not accepted as unchanged. Empty-shard checks also guard against retaining
another shard's report files when rerunning a task sequentially in a fixture.

## Gradle and JDK matrix

The [compatibility workflow](../.github/workflows/compatibility.yml) runs on branch pushes, pull
requests, manual dispatch and calls from the tag-publication workflow. Every cell runs the complete
Docker-free `check assemble`, not only plugin application or a compilation smoke test. All cells
are required; failures do not cancel the others.

| Consumer Gradle | JDK 17 | JDK 21 | JDK 25 |
| --- | --- | --- | --- |
| 9.0.0 | Required | Required | Not supported by Gradle |
| 9.1.0 | Required | Required | Required |
| 9.8.1 | Required | Required | Required |

These eight pairs are the compatibility targets for this version. A green workflow run for the
revision being released is required before claiming support. Intermediate releases, Gradle 8.x,
other JDK versions and other JDK vendors are not covered by this matrix.
Gradle 9.0.0 is the lower test boundary, 9.1.0 introduces JDK 25 runtime support, and 9.8.1 matches
the repository Wrapper. The JDK 25 exclusion follows the
[Gradle Java runtime compatibility table](https://docs.gradle.org/current/userguide/compatibility.html).

The Wrapper always builds and validates the plugin with Gradle 9.8.1. The `testGradleVersion`
property selects the distribution used by **every functional TestKit consumer**, independently
of the build Wrapper. CI installs the matrix Temurin JDK for the outer build, nested Gradle daemon
and test workers. A runtime regression test checks the actual consumer Gradle/JDK and worker JDK
so a fallback to the Wrapper or another Java installation cannot produce a misleading green cell.
Plugin bytecode remains Java 17 regardless of the selected runtime.

To reproduce one cell, select its JDK through `JAVA_HOME` and `PATH`, then run:

```shell
bash ./gradlew check assemble -PtestGradleVersion=9.0.0 -PtestJavaVersion=21
```

PowerShell, using your own JDK installation path:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat check assemble '-PtestGradleVersion=9.0.0' '-PtestJavaVersion=21'
```

Omitting the property uses the Gradle version building the plugin (the Wrapper by default);
direct IDE execution falls back to its TestKit distribution. Changing the property invalidates
the outer test task's inputs. Run local cells sequentially in one checkout, or use isolated
workspaces to prevent report/output collisions.
TestKit may download the selected Gradle distribution on first use. `testJavaVersion` asserts the
expected Java major version; it does not install or select a JDK. Without it, the expectation
defaults to the JDK building the plugin. CI always supplies both properties explicitly.

CI runs on Linux and retains uniquely named HTML/JUnit reports for every cell, including failures.
It validates the Wrapper, uses read-only repository permissions and does not publish the plugin or
require release credentials. The matrix does not run the Docker-backed examples.

The [publication workflow](../.github/workflows/publish.yml) calls this same matrix at the tagged
commit, after checking its version, and publishes only if the entire call succeeds. Workflow
regressions are checked by parsing YAML 1.2 with a test-only SnakeYAML Engine dependency. Release
guard fixtures use the fixed publishing Wrapper; consumer fixtures use `testGradleVersion`.
Neither regression test publishes anything or needs Portal credentials.

## Verified scope

The original 46-test plugin suite passed all eight target pairs locally on Windows, with no
failures or skipped tests. Release/workflow regressions are additional checks in the current suite.
Linux results come from the compatibility workflow for each revision.
Plugin classes target Java 17 bytecode. The fixtures currently use JUnit Jupiter 6.1.3, Spock 2.5 for Groovy 5.0, Groovy 5.1.3
and TestNG 7.12.0 through Gradle's native TestNG adapter.
These fixture versions document the test environment; they are not dependencies injected into
consuming builds or a promise of exhaustive framework-version coverage.

Groovy DSL, Kotlin DSL, Java Jupiter tests, nested Jupiter tests, Groovy Spock specifications and
Java TestNG classes/data-provider invocations have fixtures. Kotlin DSL coverage does not establish
coverage of Kotlin-authored test classes.
The automated matrix adds Linux coverage; a green run is required for release support.
Android, Kotlin Multiplatform, native-image tests, macOS and remote-build-cache
interoperability remain outside the verified scope.

## Runnable Testcontainers examples

The [PostgreSQL and RabbitMQ example](testcontainers-example.md) is a separate consumer build.
Compile it without Docker with `./gradlew -p examples/junit-jupiter/testcontainers integrationTestClasses`;
execute it with `./gradlew -p examples/junit-jupiter/testcontainers check` when Docker is available. Its tests
are not included in the parent plugin's `check`, and missing Docker is a failure rather than a skip.
The example has also been verified on the same Windows/Gradle/JDK target using Docker Desktop's
Linux engine, with a full run, both non-overlapping shards, configuration-cache reuse and an empty shard.

The [Spock counterpart](spock-testcontainers-example.md) is independently runnable with
`./gradlew -p examples/spock/testcontainers check`. Use `integrationTestClasses` for compilation
without Docker, and select count/index as described in its guide. Its `setupSpec()`/`@Shared`
boundary is a specification, and data-table iteration reporting does not split specification ownership.
It has been checked on the same Docker Desktop/Windows runtime: nine cases in the baseline, five
and four in the two non-overlapping shards, both data-table iterations together, configuration-cache
reuse and an empty shard with no stale reports.

The [TestNG counterpart](testng-testcontainers-example.md) runs through
`./gradlew -p examples/testng/testcontainers check`, with `integrationTestClasses` available without
Docker. It has been checked on the same Docker Desktop/Windows runtime: nine cases in the baseline,
five and four in disjoint shards, both provider rows together, one PostgreSQL/RabbitMQ startup per
worker, configuration-cache reuse and an empty shard with no stale XML. Separate Docker-free
TestKit fixtures check weighted/hash-only coverage, cache reuse and propagation of `@BeforeClass`
failures rather than accepting a successful build containing only skipped dependent tests.

## Extending coverage

For assignment changes, preserve determinism across input orders and prove exactly one owner per
family across all indices. Include equal weights, missing measurements, nested classes, negative
hashes, zero durations and arithmetic overflow. Check changes against an unsharded baseline rather
than merely counting successful jobs.

For Gradle integration changes, exercise both DSLs, delayed task registration, opt-in, existing
file restrictions, explicit `--tests`, empty shards, ordinary test failures and strict configuration
cache. Do not weaken the consumer's failure policy to make a fixture pass. Test new advertised
compatibility combinations before adding them to the supported scope.

For documentation-only changes, validate relative links and regenerate affected diagrams using
the [PlantUML workflow](diagrams/README.md). The rendered image must tell the same story as its
editable source and the current implementation.
