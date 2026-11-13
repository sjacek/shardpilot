# Maven: choose a smoke suite

[Maven examples](../README.md) · [Selection guide](../../../docs/test-selection.md)

Uses **0.5.0**, available in Maven Central. Java 17+ is required; Docker is not.
From the repository root, install the checkout with `./mvnw -B -ntp install -DskipITs` first.
The POM pins 0.5.0; the local installation above exercises changes from this checkout.
Three classes are eligible; `selection.yaml` selects only
Alpha and Beta. A one-node run is:

```shell
./mvnw -f examples/maven/test-selection/pom.xml test
```

For a fresh two-node cohort, clean only at the start:

```shell
./mvnw -f examples/maven/test-selection/pom.xml clean test-compile shardpilot:prepare -Dshardpilot.count=2
./mvnw -f examples/maven/test-selection/pom.xml test -Dshardpilot.count=2 -Dshardpilot.index=1
./mvnw -f examples/maven/test-selection/pom.xml test -Dshardpilot.count=2 -Dshardpilot.index=2
./mvnw -f examples/maven/test-selection/pom.xml shardpilot:verify -Dshardpilot.count=2
```

PowerShell uses `.\mvnw.cmd` and quotes dotted `'-Dshardpilot.count=2'` arguments.
Open `target/shardpilot/unit/selections/smoke/selection/selection.html` and `coverage.html` under
this example. Each node's native XML is under `test-results/<index>/`. Across both nodes,
Alpha/Beta must appear once each and Gamma must be absent. Maven Invoker executes this sequence
and its `verify.groovy` checks original native XML, multiplicity, outcomes and the selection plan.
The root `./mvnw verify` includes this example in its isolated local repository.

Real CI uses isolated workspaces and collects every plan/actual sidecar and native report before
aggregation. Keep all native outcomes required; class coverage alone does not establish execution.
Use identical manifest/history/version/count inputs for the entire cohort and never clean or
regenerate missing evidence during aggregation. See the guide for Failsafe, nested candidates,
focused-filter rejection and report overrides.
