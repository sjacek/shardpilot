# Maven adapter

[Documentation index](README.md) · [Runnable POMs](../examples/maven/README.md)

`io.github.sjacek:shardpilot-maven-plugin:0.3.0` provides class sharding, YAML manifests and
JSON/HTML coverage reports for native Maven Surefire and Failsafe executions. It uses the same
Java 17 core and assignment algorithm as the Gradle adapter. No coordinator or replacement test
runner is introduced. Maven owns test discovery, JVMs, dependencies and normal result handling.

The source modules are `shardpilot-core`, `shardpilot-gradle-plugin` and `shardpilot-maven-plugin`.
Gradle's plugin ID and implementation coordinates remain unchanged. Its JAR bundles the core,
so Gradle users do not need a separate core publication. Maven resolves the core as a normal plugin
dependency. See [module architecture](architecture.md#source-modules-and-artifact-compatibility).

## Build and try the checkout

The Maven Wrapper pins Maven 3.9.16 with a SHA-256-verified distribution. Use JDK 17 or newer.
The CI targets are Maven 3.9.16 with JDK 17, 21 and 25; a green run for the release revision is
required before claiming those combinations. Maven 4 is not a verified target.

From the repository root:

```shell
./mvnw -B -ntp verify
```

This builds/tests the core and Maven plugin and runs Maven Invoker fixtures plus the runnable
examples in an isolated repository under `shardpilot-maven-plugin/target/it-repository`. It does
not install the candidate into your regular repository or publish remotely. To try the POMs
directly with your usual Maven repository, first run `./mvnw -B -ntp install -DskipITs`.

On Windows use `mvnw.cmd`; quote dotted properties in PowerShell, for example
`'-Dshardpilot.count=2'`. On Unix a checkout without executable permission can use `bash ./mvnw`.
This Maven reactor builds core/Maven artifacts. Use `./gradlew check assemble` for the Gradle
adapter, including its distribution, Plugin Portal metadata and Configuration Cache tests.

## Configure a native runner

Add the adapter and wire Surefire's `excludesFile` to its generated output:

```xml
<plugin>
  <groupId>io.github.sjacek</groupId>
  <artifactId>shardpilot-maven-plugin</artifactId>
  <version>0.3.0</version>
  <executions>
    <execution>
      <id>select-unit</id>
      <goals><goal>select</goal></goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.4</version>
  <configuration>
    <excludesFile>${project.build.directory}/shardpilot/unit/excludes.txt</excludesFile>
  </configuration>
</plugin>
```

`select` defaults to `process-test-classes`, after test compilation and before Surefire. For
Failsafe, add a separate adapter execution with `<runner>FAILSAFE</runner>` bound to
`pre-integration-test`, and configure Failsafe's generated path as
`${project.build.directory}/shardpilot/integration/excludes.txt`. Retain Failsafe's normal
`integration-test` and `verify` goals. The [complete Jupiter POM](../examples/maven/junit-jupiter/pom.xml)
shows both runners, independent suites and index-specific test report directories.

Put parameters shared by explicit goals (for example `durationsFile` and maintained exclusions)
in the plugin-level `<configuration>`. Runner-specific `select` bindings belong in their execution
configuration. Explicit `shardpilot:prepare` / `shardpilot:verify` invocations use the plugin's common
configuration, not an arbitrary named selection execution; pass `-Dshardpilot.runner=FAILSAFE`
and the matching custom `suite`/directory properties when preparing or inspecting that suite.

Preparation/selection reads native plugin-level filters and the single matching execution's
configuration, including Surefire's implicit `default-test` execution overrides. It evaluates
Maven expressions and uses Surefire's own class-pattern resolver for includes, excludes, regex
patterns and include-file records. Default Surefire/Failsafe include patterns apply when unset.
Default inner-class candidate exclusion is retained; Jupiter can discover nested tests through
their selected enclosing class. Package/module metadata is never a candidate class identity.

The generated exclusion file denies every compiled class outside the selected eligible set.
For an empty shard it also denies `**/*`, preventing fallback to native default includes.
It is task-owned output: **do not point it at a maintained consumer exclusions file**. Move that
file to `additionalExcludesFile`; its patterns are included in eligibility before sharding and
therefore retained in the generated exclusions. Previous generated output is never read back as
an input. Native inline class exclusions remain supported.

## Goals and artifact flow

![Maven lifecycle selection and standalone verification](diagrams/maven-execution.svg)

[PlantUML source](diagrams/maven-execution.puml)

| Goal | Scheduling | Output / behavior |
| --- | --- | --- |
| `shardpilot:prepare` | Explicit, after `test-compile`; not automatically bound. | One baseline and all planned partitions. Never runs test goals. |
| `shardpilot:select` | Bind before the chosen native runner; default phase is `process-test-classes`. | Generated exclusions and `actual-N.yaml` before workers. Does not regenerate a plan. |
| `shardpilot:verify` | Explicit aggregation; no default phase or producer dependencies. | Reads collected evidence and writes JSON/HTML on validation success/failure; fails the build for invalid evidence. |

For one suite, a two-node flow is:

```shell
./mvnw test-compile shardpilot:prepare -Dshardpilot.count=2
# Run these in isolated CI workspaces with the same compiled inputs/history/plan:
./mvnw test -Dshardpilot.count=2 -Dshardpilot.index=1
./mvnw test -Dshardpilot.count=2 -Dshardpilot.index=2
# After downloading all actual artifacts and the common plan:
./mvnw shardpilot:verify -Dshardpilot.count=2
```

Run these commands in a consuming project containing the configured plugin, not the repository's
POM-only parent. Use `verify` instead of `test` for the complete Surefire/Failsafe example, then
verify each suite separately with the corresponding `shardpilot.runner`. See the
[example commands](../examples/maven/README.md). Always preserve the normal native test failures;
selection coverage can pass even when an assertion failed. A worker failure leaves its actual
manifest when the job workspace survives.

`mvn verify` executes the Maven lifecycle and its native result gates. `mvn shardpilot:verify`
executes only this plugin's artifact gate. Use the latter in aggregation without `clean` or a
preceding lifecycle: `clean` removes downloaded default `target/` evidence, while a lifecycle can
rerun selection/tests. `select` recomputes ownership from current inputs; it does not consume
`planned-N.yaml` or create missing planning files.

## Output layout and reactor isolation

The default layout for one non-aggregator module is:

```text
target/shardpilot/
  unit/                         # Surefire; integration/ for Failsafe
    plan/
      baseline.yaml
      planned-1.yaml
      planned-2.yaml             # one file for each index 1..count
    actual/
      actual-1.yaml
      actual-2.yaml
    excludes.txt                 # generated locally before this node's native runner
    coverage.json                # aggregation output
    coverage.html                # standalone aggregation output
```

Preparation writes `plan/`; selection writes one actual file and replaces its local `excludes.txt`;
verification writes both reports. Transfer the common plan and index-specific actual files to
aggregation. The generated exclusions are native-runner input for one node and are not coverage
evidence to merge. Normal Surefire/Failsafe XML remains outside this directory.

Each reactor module owns this layout under its own build directory, even if two modules contain
the same binary class names. Preserve module and suite paths when collecting artifacts. `-T2`
parallelizes reactor projects; it does not create shard jobs or select a shard index. POM-only
aggregators produce no plan/report. See the [reactor example](../examples/maven/reactor/pom.xml).

## Parameters

| Parameter / `-D` property | Default / contract |
| --- | --- |
| `runner` / `shardpilot.runner` | `SUREFIRE`; use `FAILSAFE` for integration candidates. |
| `shardCount` / `shardpilot.count` | `1`; positive and identical for the common plan and all nodes. |
| `shardIndex` / `shardpilot.index` | `1`; one-based selected node. Preparation/verification use count only. |
| `durationsFile` / `shardpilot.durationsFile` | Unset/missing means hash-only cold start. Existing invalid TSV fails. |
| `suite` / `shardpilot.suite` | `unit` for Surefire, `integration` for Failsafe. Custom names must be one alphanumeric path component, optionally containing `.`, `_`, `-`. |
| `directory` / `shardpilot.directory` | `${project.build.directory}/shardpilot/<suite>`. |
| `plannedDirectory` / `shardpilot.plannedDirectory` | `<directory>/plan`. |
| `actualDirectory` / `shardpilot.actualDirectory` | `<directory>/actual`. |
| `excludesFile` / `shardpilot.excludesFile` | `<directory>/excludes.txt`. The runner POM must reference this exact path. |
| `additionalExcludesFile` / `shardpilot.additionalExcludesFile` | Optional maintained file with consumer exclusions; missing configured files fail. |
| `reportFile` / `shardpilot.reportFile` | `<directory>/coverage.json`. Verification only. |
| `htmlReportFile` / `shardpilot.htmlReportFile` | `<directory>/coverage.html`, independent of JSON path. Verification only. |
| `verifyActual` / `shardpilot.verifyActual` | `true`; `false` is local plan-only inspection. |
| `requireNonEmptyShards` / `shardpilot.requireNonEmptyShards` | `true`; opt out for intentional empty partitions. Baseline must remain nonempty. |

Manifest schema, filenames, selection hashes and HTML statuses are shared with Gradle; see the
[manifest/report contract](shard-verification.md#manifest-format). Maven changes only the output
namespace from `build/` to `target/`. The Gradle task names/DSL remain adapter-specific.

## Boundaries and unsupported overrides

- One active `test` or `integration-test` runner execution per project is supported. Surefire and
  Failsafe can coexist using their separate adapter executions/namespaces. Multiple executions of
  the same native goal are rejected rather than reporting an incomplete candidate set.
  Customize Surefire through `default-test`; an additional execution would leave its implicit
  default active and can duplicate tests. Disabled runner executions are rejected, and Failsafe
  must have explicit `integration-test` and `verify` bindings.
- `-Dtest`, `-Dit.test` and native `<test>` overrides bypass ordinary runner exclusions and are
  rejected while the adapter goals run. Use a separate unsharded configuration for focused native
  runs. Class-pattern files containing method selectors are also rejected.
- Configure native class filters/file paths in the runner POM. CLI/property overrides of
  `surefire/failsafe.includes`, `.excludes`, `.includesFile`, `.excludesFile` or `.testClassesDirectory` are rejected so they
  cannot silently bypass the recorded selection.
- Dependency-scanned tests and TestNG `suiteXmlFiles` are not supported for candidate coverage.
  Explicit runner configuration for them fails. Framework tags/groups and skipped methods remain
  outside class-candidate manifests, as with Gradle.
- Skipped native runs (`skipTests`, `maven.test.skip`, Failsafe `skipITs`) cannot produce actual
  execution-selection evidence through these goals and fail early. Preparation also validates
  this runner configuration; `test-compile shardpilot:prepare` needs no skip flag.
- POM-only reactor aggregators are skipped. Every child module has its own plan/report namespace;
  do not flatten module artifacts during CI collection. No shared project/session properties are
  mutated, supporting independent module builds and Maven `-T` execution.
- Maven controls JVM options and `forkCount`/`reuseForks` through Surefire/Failsafe. Gradle's
  `testProcesses` DSL and `forkEvery=10` are not ported as an equivalent Maven control.

## CI and troubleshooting

The [Maven CI sequence](ci.md#maven-ci-sequence) maps preparation, native execution and aggregation
to required jobs, including the two-suite flow and module-aware artifact collection.

Publish both `target/shardpilot/<suite>/coverage.json` and `coverage.html` even after verification
fails. A lightweight HTML-only artifact publisher can expose the same GitLab MR link as a Gradle
consumer; see the [publisher pattern](shard-verification.md#finding-a-class-assignment-in-gitlab),
substituting `target/` paths. Scheduling after failed nodes and uploading after failed scripts are
separate CI decisions. Keep every native test job and coverage validation required.

| Symptom | Check |
| --- | --- |
| Plugin/core cannot be resolved | Install the root Maven reactor locally for development, or configure a repository containing the parent, core and adapter. Gradle Portal publication/composite substitution does not install Maven artifacts. |
| No plugin found for prefix `shardpilot` | Declare the adapter/version in the consumer's build plugins. A fully qualified invocation such as `io.github.sjacek:shardpilot-maven-plugin:0.3.0:verify` bypasses prefix lookup but still requires resolvable artifacts. |
| Missing compiled test directory | Run `test-compile` before explicit preparation/selection. Standalone verification never compiles missing evidence. |
| Runner `excludesFile` wiring error | Match the configured native output path to `shardpilot.excludesFile`, including suite/directory overrides. |
| All classes disappeared on a later node | Ensure a generated file was not configured as a maintained input; the adapter intentionally ignores its own previous output. |
| Expected class missing from the baseline | Check native includes/excludes, include-file records and `additionalExcludesFile`; use the same effective model and history on every node. |
| Empty shard | A generated deny-all file prevents fallback. Opt out of the nonempty policy only intentionally and retain native test failure policy. |
| `verify` fails after a test assertion failed | Check manifests separately from test results. A passing coverage report would not override that failed native test job either. |
| Reports contain unavailable evidence | Collect exactly one cohort with all index-specific artifacts from the same project/suite/run. |

Reports are standalone and deterministic; output I/O is not an atomic multi-file transaction.
Use the build outcome as authoritative after a write failure. See [testing scope](testing.md) for
the native Maven fixtures and the Gradle compatibility regressions.

## Publishing ShardPilot itself

The tag workflow publishes the parent, core and adapter to Maven Central through an explicit
`central-release` profile. Consumers need neither that profile nor signing credentials. Maintainers
can follow the [Central token/key setup](maven-central.md) and [shared release procedure](releases.md).
The existing Gradle distribution and plugin marker are published by a separate job.
