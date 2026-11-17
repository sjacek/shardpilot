# Maven changed-test, module and contract selection

Development-checkout example; published 0.5.0 does not contain policy v2. First install the
candidate using the repository's verified Maven entry point, as described in the
[Maven example index](../README.md). See the
[shared impact guide](https://github.com/sjacek/shardpilot/blob/main/docs/selection-impact.md).

From this directory, after installing the candidate:

```shell
../../../mvnw test
```

The default `smoke-testdata` executes AlphaTest and BetaTest without Git. The same v2 policy as
the Gradle example also has `impact-testdata`: directly changed AlphaTest, a changed `library`
provider whose `app` consumer requires BetaTest, and JSON/YAML contract rules requiring GammaTest.
UnusedTest stays eligible but unrelated. No selector script is installed in the consumer.

For adoption, adjust source roots and module/contract globs to the **Git repository root**,
then choose a committed base:

```shell
../../../mvnw test -Dshardpilot.selectionProfile=impact-testdata -Dshardpilot.gitBase=main
```

The POM references property defaults so `-Dshardpilot.*` overrides work. This fixture deliberately
uses strict `fail` fallbacks: dirty or unrelated/unclassified changes must not silently narrow
coverage. Configure `full` when a full run is the desired fallback. Native Surefire eligibility,
exclusion files, process failures and reports remain authoritative.

For two nodes, run `test-compile shardpilot:prepare -Dshardpilot.count=2` once with the chosen
policy/profile/base. Transport its complete `selection/` and `plan/` artifacts into independent
workers, using a separate ignored directory for `selectionSnapshotDirectory`. Use identical
inputs and distinct indices, retain original native XML/actual manifests, then aggregate with
`shardpilot:verify`. Do not regenerate a selection independently on each node.

The repository's native impact Invoker fixture additionally runs this flow with both Surefire
and Failsafe. Failsafe explicitly binds `integration-test` and `verify`, uses its own runner and
report namespace, and consumes the same shared v2 policy. Independent XML checks require exactly
AlphaTest, BetaTest and GammaTest once for each runner. The example's `verify.groovy` is a test
oracle for its default smoke run, not a consumer selection algorithm.
