# Configurable profiles (Groovy DSL)

Development-checkout feature; published ShardPilot 0.5.0 does not expose profile parameters.
See the [common profile and Git contract](https://github.com/sjacek/shardpilot/blob/main/docs/selection-policy.md).
This standalone build uses the repository composite, the existing Wrapper and reviewed dependency
versions. No Docker or selector script is required. TestKit runs this consumer against the candidate.

From this directory, run the root Wrapper:

```shell
../../../../gradlew test
../../../../gradlew test -Pshardpilot.selectionProfile=affected-testdata -Pshardpilot.gitBase=main
```

The default `smoke-testdata` executes AlphaTest and BetaTest. `full-testdata` executes all three.
Affected rules are synthetic: adapt `policy.yaml` to the real project's paths and mandatory tests.
The example itself lives in a larger repository; unmatched changes use the declared full fallback.
Only committed changes are supported, and a dirty checkout also falls back to full by default.

For several nodes, prepare once with `prepareTestShardManifests -Pshardpilot.count=2`,
transport the resulting `build/shardpilot/test/selections/profile-smoke-testdata/selection`
to a separate ignored input directory, then pass
`-Pshardpilot.selectionSnapshotDirectory=build/downloaded-selection` to every `test` node.
Use the same count/profile/history and pass a different one-based `shardpilot.index` to each node.
Keep the original plan and all actual manifests before running `verifyTestShards`.
Do not clean between nodes. Native failures remain fatal; coverage is not an execution-success proof.
