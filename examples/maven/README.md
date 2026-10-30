# Maven examples

[All examples](../README.md) · [Maven plugin guide](../../docs/maven.md)

| Example | Exercises |
| --- | --- |
| [JUnit Jupiter](junit-jupiter/pom.xml) | Surefire and Failsafe, nested tests, custom `Spec` suffix, consumer exclusions, separate suite manifests/reports. |
| [Multi-module reactor](reactor/pom.xml) | Independent per-module plans and native test execution with `-T2`. POM-only aggregator modules are skipped. |

From the repository root, install the Maven modules into your local repository first:

```shell
./mvnw -B -ntp install -DskipITs
```

This builds/tests the core and adapter and installs local artifacts; it does not publish remotely.
`./mvnw verify` also runs the examples and negative Invoker fixtures in an isolated repository,
without requiring installation into your regular local repository.

For the Jupiter example, prepare both suite plans, execute every node and verify both reports:

```shell
./mvnw -f examples/maven/junit-jupiter/pom.xml test-compile shardpilot:prepare -Dshardpilot.count=2
./mvnw -f examples/maven/junit-jupiter/pom.xml shardpilot:prepare -Dshardpilot.count=2 -Dshardpilot.runner=FAILSAFE
./mvnw -f examples/maven/junit-jupiter/pom.xml verify -Dshardpilot.count=2 -Dshardpilot.index=1
./mvnw -f examples/maven/junit-jupiter/pom.xml verify -Dshardpilot.count=2 -Dshardpilot.index=2
./mvnw -f examples/maven/junit-jupiter/pom.xml shardpilot:verify -Dshardpilot.count=2
./mvnw -f examples/maven/junit-jupiter/pom.xml shardpilot:verify -Dshardpilot.count=2 -Dshardpilot.runner=FAILSAFE
```

Use `mvnw.cmd` and quote dotted `'-Dshardpilot.count=2'` arguments in PowerShell. Open each suite's
`target/shardpilot/unit/coverage.html` or `target/shardpilot/integration/coverage.html` under the
example directory. Each node's JUnit reports are also index-specific, so the sequential demonstration
does not overwrite them. Real CI uses isolated workspaces and collects the common plan plus all
`actual-N.yaml` files before verification.

For the reactor, use the same prepare/test/verify sequence with `-f examples/maven/reactor/pom.xml`
and `-T2`. Each child has its own `target/shardpilot/unit` directory. Do not merge modules' plans
into one flat artifact namespace.

```shell
./mvnw -T2 -f examples/maven/reactor/pom.xml test-compile shardpilot:prepare -Dshardpilot.count=2
./mvnw -T2 -f examples/maven/reactor/pom.xml test -Dshardpilot.count=2 -Dshardpilot.index=1
./mvnw -T2 -f examples/maven/reactor/pom.xml test -Dshardpilot.count=2 -Dshardpilot.index=2
./mvnw -T2 -f examples/maven/reactor/pom.xml shardpilot:verify -Dshardpilot.count=2
```

`-T2` parallelizes the two reactor modules; the count/index properties partition classes within
each module. Open `first/target/shardpilot/unit/coverage.html` and
`second/target/shardpilot/unit/coverage.html` under the reactor example after aggregation.
Run the sequence without `clean` between nodes or before aggregation so it retains all evidence.
To start a new local cohort, clean first and repeat preparation and every node; do not reuse a
previous run's actual files. For production CI, follow the [isolated job sequence](../../docs/ci.md#maven-ci-sequence).

## Refresh both suite histories

After the complete two-node Jupiter sequence above, invoke artifact-only refresh for each suite:

```shell
./mvnw -f examples/maven/junit-jupiter/pom.xml shardpilot:refresh-durations -Dshardpilot.count=2 -Dshardpilot.minimumCoverage=1
./mvnw -f examples/maven/junit-jupiter/pom.xml shardpilot:refresh-durations -Dshardpilot.count=2 -Dshardpilot.minimumCoverage=1 -Dshardpilot.runner=FAILSAFE
```

Default report roots recursively find this example's `shard-1` and `shard-2` directories.
Each suite's AUTO source is its prepared `plan/baseline.yaml`. Inspect `durations.tsv` and
`durations.json` under `target/shardpilot/unit` and `integration`: three unit families and two
integration families are measured at full coverage. The shared maintained `durations.tsv` at
the project root remains unchanged; foreign records from the other suite are pruned from each
candidate. No native test executes during refresh. The Invoker hook verifies both candidates,
the unchanged maintained weights, selection manifests, HTML and unique native testcase results.
It also derives expected seconds from each suite's native XML, including inclusive Jupiter nested
timing, applies the documented default floor/precision and compares every candidate value.
Merge/coverage counters and report namespaces must agree with the independent suite baselines.

For collected CI outputs, set `shardpilot.reports` to that suite's collected root; do not mix
Surefire and Failsafe. See [history rules](../../docs/history-refresh.md) and the
[three-node CI examples](../ci/README.md). No candidate is adopted automatically.

Import a Maven example's `pom.xml` into the IDE as an independent project. `invoker.properties`
and `verify.groovy` are build-test hooks used by the repository's Maven Invoker tests; ordinary
example builds do not execute those scripts.
