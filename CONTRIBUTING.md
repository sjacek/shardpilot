# Contributing to ShardPilot

ShardPilot is MIT-licensed. Only submit code and examples that you have the right to contribute.
Preserve third-party attribution and license requirements; do not copy application-specific code,
credentials, customer data, private repository addresses or captured application logs into fixtures.

Follow the [coding standards](docs/CODING_STANDARDS.md) for declaration layout, CI example validation
and handling IDE diagnostics or inspection suppressions.

## Source layout and local development

The sibling modules are `shardpilot-core`, `shardpilot-gradle-plugin` and `shardpilot-maven-plugin`.
Keep assignment, YAML encoding, XML timing normalization, history refresh and report semantics in
the core; it has no Gradle/Maven API imports.
Keep build-tool lifecycle, selection filters and runner configuration in the corresponding adapter.
See the [module diagram](docs/architecture.md#source-modules-and-artifact-compatibility).

| Entry point | Modules / outputs |
| --- | --- |
| Root `settings.gradle` / `build.gradle` | Core and Gradle adapter; separate module `build/` directories. |
| Root `pom.xml` | Core and Maven adapter; separate module `target/` directories. |
| `examples/gradle/<framework>/testcontainers` | Independent Gradle consumers using the repository-root composite. |
| `examples/gradle/junit-jupiter/duration-history` | Docker-free consumer; its build/test inputs also run through TestKit in the Gradle adapter suite. |
| `examples/maven/junit-jupiter` / `reactor` | Independent Maven consumers; Invoker clones them into isolated test outputs. |

Import the root Gradle settings for Gradle development and the root POM for Maven development.
In IntelliJ IDEA, use the root Gradle Wrapper and a supported Gradle JVM, then synchronize
the Gradle project after a wrapper update. Gradle API imports in build scripts come from that
imported Gradle model. Independent examples are not root subprojects: link the example's
`settings.gradle` separately when editing it, using the root Wrapper's Gradle distribution
for examples without their own wrapper. See [IDE troubleshooting](docs/troubleshooting.md#gradle-api-imports-in-intellij-idea).
Both builds compile the same core sources, using separate outputs. Run Gradle verification
sequentially in one checkout: concurrent TestKit/example/composite builds can overwrite the same
plugin outputs. Maven reactor `-T` support concerns independent project namespaces, not concurrent
builds writing the same `target/` directory.

For a Gradle consumer, keep `includeBuild` at the repository root. For a Maven consumer, install
the reactor locally with `./mvnw -B -ntp install -DskipITs`; see the
[example commands](examples/maven/README.md). The root install skips Invoker only when this flag
is supplied; it still runs unit tests. Consumer `-DskipITs` is a different use of that flag and is
rejected by active Failsafe selection/preparation.

The Gradle distribution bundles the core and retains `io.github.sjacek:shardpilot` plus the
unchanged plugin marker. The Maven distribution depends on separately resolved core/parent
artifacts. Update all Maven project/parent/example versions with the root Gradle version and
review both distribution boundaries before release. Keep generated descriptors and build outputs
out of source control; never edit generated Maven plugin descriptors by hand.

## Local checks

Run `./gradlew check assemble` (or `gradlew.bat check assemble` on Windows) before proposing a change.
Functional tests launch real Gradle TestKit builds and fetch fixture dependencies from Maven Central.
Also run `./mvnw -B -ntp verify` (`mvnw.cmd` on Windows) for shared-core/Maven changes. It runs
core/adapter unit tests and native Surefire/Failsafe Invoker fixtures/examples with an isolated
test repository. Root Gradle checks aggregate the core and Gradle adapter, not the native Maven reactor.

For Gradle integration changes, require all eight jobs in the
[Gradle/JDK compatibility matrix](docs/testing.md#gradle-and-jdk-matrix). Reproduce a cell with its
JDK selected and `bash ./gradlew check assemble -PtestGradleVersion=9.0.0 -PtestJavaVersion=21`
(substitute the target versions).
Keep the workflow and documented matrix aligned when changing the supported runtime boundaries.
The same required workflow also runs Maven 3.9.16/JDK 17, 21 and 25. Keep parent/module Maven
versions aligned with the evaluated Gradle release version. Do not rename Gradle's published
implementation coordinates when changing source-module names.

Keep the implementation framework-neutral. Do not introduce a coordinator service, automatic retries,
runtime test dependencies, implicit subproject configuration, or a CI-provider dependency into the core.

Any assignment change needs regression coverage proving determinism and complete, non-overlapping
class ownership. Changes to Gradle integration need TestKit coverage with strict configuration cache,
existing test filters, task opt-in and failed test propagation. Update both Groovy and Kotlin DSL examples
when changing the public extension. Do not advertise a compatibility combination without testing it.

History is untrusted metadata: reject malformed numbers and identities, and do not print raw lines.
The XML timing reader disables DTDs and external entities and reads timing metadata only. Preserve
that boundary when extending it; retry/failure payloads and arbitrary parser diagnostics must not
leak into lifecycle messages or refresh JSON. Shared rules belong in the core; each adapter owns
source selection, input/output protection and build-tool failure handling. See the
[refresh contract](docs/history-refresh.md) for complete-family merging and candidate cleanup.

For changes to the runnable history example or its CI transport, focused checks are:

```shell
./gradlew :shardpilot-gradle-plugin:test --tests 'io.github.sjacek.shardpilot.HistoryRefreshExampleFunctionalTest' --tests 'io.github.sjacek.shardpilot.HistoryRefreshCiExamplesTest'
./mvnw -B -ntp verify -Dinvoker.test=junit-jupiter
```

The first command runs the actual Gradle example through TestKit plus structural CI checks.
The second runs core/Maven unit tests and selects the Jupiter example, including Surefire and
Failsafe, from Invoker. It does not run the other native fixtures or reactor example; retain
the full checks above for shared implementation/release verification. Quote dotted `-D`/`-P`
arguments in PowerShell. Example source/configuration files are declared Gradle test inputs;
keep that input set aligned when adding files the functional example test reads. Generated
`build/` or `target/` evidence must never become a fixture input.

For manifest changes, update the [YAML schema and artifact lifecycle](docs/shard-verification.md).
Test wrong schemas/types, duplicate keys/names, canonical hashes, missing/stale/swapped evidence
and failed-test preservation. Aggregation must never manufacture missing CI downloads. Keep both
selection coverage and normal execution outcomes as independent required gates.

For report changes, keep JSON's machine-readable contract and HTML's human-readable status
semantics aligned with the same evidence snapshot. Cover partial/malformed/missing evidence,
plan-only mode, artifact-derived text escaping and replacement of earlier successful outputs.
Keep HTML standalone and update the [status reference](docs/shard-verification.md#reading-html-statuses),
both DSL path examples and relevant diagrams when the output contract changes.

Public repository ownership, release credentials and publication approval remain with the maintainer.

Publication changes also require `bash scripts/verify-maven-release.sh` (Bash, GnuPG and a JDK).
It builds the actual `central-release` profile with a disposable key, validates the parent/core/adapter
bundle and cryptographically verifies every signature without uploading. It is the twelfth required
compatibility job, alongside eight Gradle and three Maven/JDK jobs. Keep its key and bundle out of
release uploads. [Central setup](docs/maven-central.md) documents the maintainer's real credentials.
Workflow and generated Gradle metadata regressions run in the Gradle adapter's test suite.

## Documentation changes

Start at the [documentation index](docs/README.md). Keep examples aligned with the public extension
and distinguish implemented behavior from proposed work. When changing the architecture or assignment
algorithm, update the related PlantUML source and regenerate its SVG preview; see the
[diagram maintenance instructions](docs/diagrams/README.md). Do not edit generated SVGs by hand.
