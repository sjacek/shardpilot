# Maven configurable profiles

Development-checkout feature; first install this candidate with `./mvnw install -DskipITs` from
its repository root. Published 0.5.0 does not expose these parameters. See the
[common profile and Git contract](https://github.com/sjacek/shardpilot/blob/main/docs/selection-policy.md).

The POM defines a shared YAML policy, ordinary native Surefire wiring and independently discovered
eligibility. Property defaults keep the profile and policy file configurable with `-Dshardpilot.*`.
The Invoker test prepares two nodes, uses a separate worker output namespace and verifies both
nodes' original native XML, without adding any selector script to the consumer.

From this directory, using an installed candidate:

```shell
../../../mvnw test
../../../mvnw test -Dshardpilot.selectionProfile=affected-testdata -Dshardpilot.gitBase=main
```

Default smoke selection contains AlphaTest and BetaTest. Full selection also contains GammaTest.
The synthetic affected rules must be adapted to real application paths. Unknown impact falls back
to full by default. For parallel jobs, prepare once with `test-compile shardpilot:prepare`, distribute
its complete `selection/` and `plan/` artifacts, and supply `selectionSnapshotDirectory` to workers.
Downloaded snapshots must remain outside generated runtime/plan/actual outputs.

`invoker.properties` demonstrates the same sequence locally by keeping the producer directory
read-only and assigning workers to `target/runtime`. Real CI normally downloads artifacts into
`target/downloaded-selection` in independent workspaces. `verify.groovy` is a repository test oracle
for the example's native XML, not a consumer selection algorithm. Keep all native failure gates.
