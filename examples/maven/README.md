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

Import a Maven example's `pom.xml` into the IDE as an independent project. `invoker.properties`
and `verify.groovy` are build-test hooks used by the repository's Maven Invoker tests; ordinary
example builds do not execute those scripts.
