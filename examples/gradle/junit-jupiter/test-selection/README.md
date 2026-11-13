# Gradle: choose a smoke suite

[Gradle examples](../../README.md) Â· [Selection guide](../../../../docs/test-selection.md)

Prepared for **0.5.0, not yet published**. Run from the repository root with Java 17+ and the
Wrapper. The composite resolves this checkout; Docker and a published plugin are not required.
Three classes are eligible. `selection.yaml` chooses Alpha and Beta, leaving Gamma out.

```shell
./gradlew -p examples/gradle/junit-jupiter/test-selection test
```

A single node runs exactly two native tests. For a separate two-node cohort, clean once before
preparing, then keep every original plan/actual file and each node's XML:

```shell
./gradlew -p examples/gradle/junit-jupiter/test-selection clean prepareTestShardManifests -Pshardpilot.count=2
./gradlew -p examples/gradle/junit-jupiter/test-selection test -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew -p examples/gradle/junit-jupiter/test-selection test -Pshardpilot.count=2 -Pshardpilot.index=2
./gradlew -p examples/gradle/junit-jupiter/test-selection verifyTestShards -Pshardpilot.count=2
```

PowerShell uses `.\gradlew.bat` and quotes dotted `'-Pshardpilot.count=2'` arguments.
The XML path override is configured after `selection {}` establishes its defaults, so local
nodes retain separate directories. Each node runs one selected class because the maintained durations balance these two families.
Open `build/shardpilot/test/selections/smoke/selection/selection.html` and `coverage.html` under
this example. Native XML is under `test-results/`; confirm Alpha/Beta appear once in total and
Gamma is absent. Require native results as well as coverage success. Real CI uses isolated
workspaces and downloads the original cohort before verification; never clean during aggregation.

This example uses normal Jupiter discovery. It does not infer tests from source changes or prove
that smoke tests are sufficient. Keep manifest/history/count fixed across jobs. See the guide for
nested eligibility, focused-filter rejection and cohort mismatch handling. After publication,
external consumers can replace `includeBuild` with plugin version `0.5.0`.
