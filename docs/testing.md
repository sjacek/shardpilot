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
| `ShardPilotFunctionalTest` | Real Gradle tasks with Jupiter/Spock, weighted/hash-only partitions, opt-in, filters, custom tasks, empty results, failures, Kotlin DSL and configuration-cache reuse. |
| `NormalizeSvgTest` | Standalone preview normalization, text/geometry/link preservation, XML input safety, idempotence and committed SVG compatibility checks. |

Functional partition checks compare the union of shard results to an unsharded baseline and check
for duplicates. Configuration-cache checks reuse a build, then change timing contents to verify
that old test results are not accepted as unchanged. Empty-shard checks also guard against retaining
another shard's report files when rerunning a task sequentially in a fixture.

## Verified scope

The initial local verification uses Gradle 9.8.1 on Windows with JDK 25. Plugin classes target Java
17 bytecode. The fixtures currently use JUnit Jupiter 6.1.3, Spock 2.5 for Groovy 5.0 and Groovy 5.1.3.
These fixture versions document the test environment; they are not dependencies injected into
consuming builds or a promise of exhaustive framework-version coverage.

Groovy DSL, Kotlin DSL, Java Jupiter tests, nested Jupiter tests and Groovy Spock specifications
have fixtures. Kotlin DSL coverage does not establish coverage of Kotlin-authored test classes.
Other operating systems, Gradle/JDK combinations, Android, Kotlin Multiplatform, native-image tests
and remote-build-cache interoperability are not yet covered by this initial verification matrix.

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
