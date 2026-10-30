# Jupiter: refresh duration history without Docker

[Gradle examples](../../README.md) · [History contracts](../../../../docs/history-refresh.md)

This independent consumer resolves the local checkout through an included build. It runs two
families (one has a nested test), prepares/verifies YAML manifests and generates candidate
TSV/JSON from both nodes' existing XML. Synthetic old weights include one removed class to
demonstrate pruning. No published 0.4.0 plugin, database, broker or Docker is required.

For IntelliJ IDEA editing, link this directory's `settings.gradle` as a separate Gradle
project. It is not a root subproject and has no separate Wrapper: select the root Wrapper's
Gradle distribution and a supported Gradle JVM, then synchronize. An unresolved Gradle
`Provider` import indicates a missing or stale IDE Gradle model; see
[IDE troubleshooting](../../../../docs/troubleshooting.md#gradle-api-imports-in-intellij-idea).

From the ShardPilot root, start a fresh cohort and run every step in order:

```shell
./gradlew -p examples/gradle/junit-jupiter/duration-history clean prepareTestShardManifests
./gradlew -p examples/gradle/junit-jupiter/duration-history test -Pshardpilot.count=2 -Pshardpilot.index=1
./gradlew -p examples/gradle/junit-jupiter/duration-history test -Pshardpilot.count=2 -Pshardpilot.index=2
./gradlew -p examples/gradle/junit-jupiter/duration-history verifyTestShards refreshTestShardDurations
```

Use `gradlew.bat` and quote dotted properties in PowerShell. Sequential local execution is only
a demonstration: reports are stored under `build/results/shard-1` and `shard-2` so neither run
overwrites the other. Run shards in separate workspaces in production. Do not insert `clean`
before aggregation, mix the optional full-run XML with shard XML, or copy old reports into the tree.

Under the example's `build/shardpilot/test/`, inspect `plan/baseline.yaml`, both `planned-N.yaml`
and `actual/actual-N.yaml`, `coverage.json`, `coverage.html`, `durations.tsv` and `durations.json`.
Refresh AUTO uses verification's baseline even though history was configured first. Coverage is
`1.0`, two families are measured, and `example.RemovedTest` is pruned. Actual times vary, including
zero-duration tiny methods; the configured floor is applied once per family.

`durations.tsv` in the project root stays unchanged. Review the candidate before a separate
adoption commit. Repeating only the last command can reuse Configuration Cache and up-to-date
outputs; it must not run tests or compile classes. The parent plugin's `check` does not launch
this independent included-build entry point. Its actual build/test sources are copied into a
TestKit consumer in the parent's `check`, substituting only plugin resolution. That regression
also drops one node's XML after success to prove failed refresh removes the stale candidate,
retains maintained history and recovers from the restored original evidence. See the
[three-node GitLab example](../../../ci/gradle/.gitlab-ci.yml) for isolated collection.

With this example's `minimumCoverage=1.0`, missing one node's XML fails history refresh even if
`verifyTestShards` remains green: that task checks class-selection manifests. The
[recovery guide](../../../../docs/history-refresh.md#recover-a-missing-shard-download) explains
the separate outcomes and restoration procedure. Its generic CI commands use count 3; this
local example keeps the count 2 from verification and needs the example path shown above.
