# Gradle changed-test, module and contract selection

Development-checkout example; published 0.5.0 does not contain policy v2. The composite build
uses the candidate from this repository. See the
[shared impact guide](https://github.com/sjacek/shardpilot/blob/main/docs/selection-impact.md)
for the complete parameters, fallbacks and JSON Pointer contract.

From this directory:

```shell
../../../../gradlew test
```

The default `smoke-testdata` executes AlphaTest and BetaTest without Git. Four classes are
eligible; the remaining two demonstrate that selection does not execute the whole suite.

`impact-testdata` demonstrates a directly changed AlphaTest source, a provider `library` whose
consumer `app` owns BetaTest, and JSON/YAML payments-contract changes that require GammaTest.
UnusedTest remains eligible but unrelated. All three impact mechanisms are plugin configuration
in `policy.yaml`, with no selector script or callback.

To adopt this policy in another project, adjust roots/globs to that project's **Git root**,
keep targets inside native eligibility and supply an existing committed base:

```shell
../../../../gradlew test -Pshardpilot.selectionProfile=impact-testdata -Pshardpilot.gitBase=main
```

The demonstration uses strict `fail` fallbacks. A dirty checkout, unknown/unclassified changes,
or an empty result must fail; it cannot claim safe selection from this repository's unrelated
development diff. Use `full` fallbacks when complete execution is the desired safe alternative.

For parallel CI, prepare once with `prepareTestShardManifests -Pshardpilot.count=2` and the same
policy/profile/base. Transfer the complete `selection/` and `plan/` artifacts, download the
selection into a separate ignored directory, and pass its `selectionSnapshotDirectory` plus
distinct `shardpilot.index` to each worker. Retain native XML and actual manifests before
`verifyTestShards`. The normal smoke sequence is also shown in the
[profile example](../selection-profiles/README.md).

Repository TestKit creates committed synthetic Git changes without committing to the developer
checkout. It checks both nodes' native XML, the exact Alpha/Beta/Gamma union, an unchanged base
snapshot after a moving ref, and rejection of tampered contract evidence before tests start.
