# Fresh Gradle and Maven release consumers

[Documentation index](README.md) · [0.6.0 release](releases/0.6.0.md)

This acceptance check exercises packaged bytes rather than a composite build or cached local
plugin. It is maintainers' release tooling; application users configure the Gradle/Maven plugin
and policy. No additional selector script is required in a consuming application.

## What is checked

The [driver](https://github.com/sjacek/shardpilot/blob/main/scripts/release_consumers.py) prepares
an exact fifteen-file publication cohort: Gradle marker, POM/module metadata, binary and
source/Javadoc JARs, plus Maven parent/core/plugin POMs and binary/source/Javadoc JARs.
SHA-256 and byte counts bind every file. Each stage rechecks the receipt; Maven's isolated
candidate repository must match it before a disposable checksum policy is generated.
Gradle retains native strict verification and receives only exact artifact pins. Neither
tool gets a trusted-group exception or an entry in the committed third-party checksum policy.

New consumer projects and homes receive downloaded dependencies/distributions only. Producer
outputs, cached ShardPilot coordinates, user settings, init scripts and credentials are excluded.
The Gradle consumer uses a versioned plugin marker without an included source build. Maven
uses the declared version in an isolated repository. Workers and aggregation run offline.

| Scenario, for each tool | Required observation |
| --- | --- |
| `smoke-testdata` | Two of three eligible native tests, two workers, successful C2/C3 verification. |
| `full-testdata` | All three eligible tests, with the same complete eligibility. |
| `acceptance-affected-testdata` | A clean local clone supplies a real committed diff; mandatory login and the configured payment probe execute. No commits are created. |
| Changed compiled bytes | Both workers and aggregation reject; no native worker XML is produced. |
| Wrong revision | Both workers and aggregation reject for the revision mismatch; no native worker XML is produced. |

The affected fixture deliberately uses a broad rule over the release source diff to test
published Git/profile plumbing. Production policies need reviewed ownership, modules and
contract pointers. A frozen decision is retained independently and must remain byte-identical
after native execution and aggregation. Native process failures stay independent of evidence
validation; synthetic local run/job metadata is labelled and proves no remote CI identity.

## Candidate packages before publication

From the ShardPilot checkout, after preparing the normal verified dependency caches:

```shell
python3 -B scripts/release_consumers.py \
  --output build/release-consumers \
  --gradle-command-json '["bash", "./gradlew"]' \
  --maven-command-json '["bash", "./mvnw"]' \
  --maven-repository /private/warmed-maven-repository
```

On Windows, pass JSON arrays containing the absolute `gradlew.bat` and `mvnw.cmd` paths.
Use a supported JDK and a fresh output directory. `--gradle-home` defaults to the configured
Gradle user home; only its dependency/distribution downloads are seeded. The Maven repository
must contain the reviewed producer and example dependencies. The normal CI warmup supplies both.

The producer publishes Gradle into an opt-in **file repository** and builds/installs Maven
packages without deploy, signing or upload. Candidate bytes come from that invocation's
producer publications, never an old cache. The receipt records the source base commit and
whether the working tree differs, so local preparation is distinguishable from a clean CI
release commit. `report.json` is written only when all ten scenarios pass.

Preparation is offline by default for consumer dependency resolution. For a cold, explicitly
networked preparation use `--online-prepare`; worker and aggregate execution still stay offline.
This is a dependency-resolution option, not a weaker checksum mode. The separate published
artifact audit supplies network-namespace isolation and strace evidence.

CI runs this check in the already-required **Gradle 9.9.1 / JDK 17** cell after the normal
native workflows. It restores both dependency snapshots and retains receipt, results and
failure logs. It does not upload consumer homes or the staging repository, change required
check names, or use publication credentials. The reusable release matrix therefore includes
this gate without a new administrative ruleset change.

## Fresh consumers after publication

First run the [full published audit](published-artifact-audit.md) explicitly for `0.6.0`
after both registries expose it. Keep the signed source tag and complete original evidence.
Then import that audit into fresh consumers:

```shell
python3 -B scripts/release_consumers.py \
  --registry-directory build/published-artifact-audit \
  --output build/registry-release-consumers \
  --gradle-command-json '["bash", "./gradlew"]' \
  --maven-command-json '["bash", "./mvnw"]' \
  --maven-repository /private/warmed-maven-repository
```

The importer re-reads the existing signature, tag/source, registry-byte and isolated execution
checks; a success flag alone is insufficient. It rejects a different audited version, stages
only the authenticated registry files and uses them for every consumer. The result identifies
`audited-registries` rather than `candidate-publication`. This path can be prepared and regression
tested before a version is published. Registry-mode acceptance for 0.6.0 is now possible with
the [verified audit evidence](published-artifact-audit.md#published-060-baseline-18-november-2026);
record that run separately from candidate-mode acceptance.

The default published/audited baseline is [0.6.0](published-artifact-audit.md#published-060-baseline-18-november-2026),
following publication and the successful full audit. The separate registry-mode profile/C2/C3
acceptance remains a follow-up; the retained 0.6.0 audit covers its documented four-framework
execution fixtures. Benchmarking remains deferred.
