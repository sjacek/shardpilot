# Publication descriptions

[Documentation index](README.md) · [Release procedure](releases.md)

These English descriptions explain the purpose and scope of the 0.3.1 artifacts. They are
configured in the build metadata that will be submitted with a release. The metadata files are
the source of truth; update the matching text here when changing them. Published earlier versions
keep their own release metadata.

## Maven Central: Maven plugin

| Field | Value |
| --- | --- |
| Name | ShardPilot Maven plugin |
| Coordinates | `io.github.sjacek:shardpilot-maven-plugin:0.3.1` |
| Packaging | `maven-plugin` |
| Description source | [Adapter POM](../shardpilot-maven-plugin/pom.xml) |
| Project and SCM URL | [sjacek/shardpilot](https://github.com/sjacek/shardpilot) |
| License | MIT |
| Developer | [sjacek](https://github.com/sjacek) |

The homepage is the POM's direct `project/url` field. The parent, core and Maven adapter each
declare `https://github.com/sjacek/shardpilot` explicitly, because an artifact listing may read
the uploaded POM without resolving inherited metadata. The release-bundle gate checks both the
effective model and this direct field. The 0.3.0 core and adapter POMs inherited the URL only;
the explicit-field correction is included in 0.3.1, since published POMs are immutable.

The POM `description` is plain text, ready for the artifact listing:

> ShardPilot helps shorten CI feedback for large JVM unit and integration test suites by splitting Maven Surefire and Failsafe test classes into deterministic, non-overlapping shards for parallel CI jobs. It balances class families using historical test durations and uses stable hashing when timing data is unavailable. The plugin preserves native test execution and configured class include/exclude patterns, with independent suites for unit and integration tests. The prepare, select and verify goals produce readable YAML manifests and standalone JSON and HTML reports to compare planned class assignments with selections collected from every CI job. Supports single-module projects and parallel Maven reactor builds without a coordinator, replacement test runner or telemetry. Your build controls test JVMs and normal test outcomes; your CI launches every shard and collects the results. Class-selection verification complements the native test result checks.

See the [Maven setup guide](maven.md) and [complete POM examples](../examples/maven/README.md) for
native runner wiring, lifecycle phases, suite configuration and supported filters. The native Maven
fixtures exercise JUnit Jupiter; the description deliberately does not transfer Gradle's framework
compatibility claims to Maven. Maven consumers configure JVM options through Surefire/Failsafe.

## Maven Central: supporting artifacts

These artifacts have their own descriptions, rather than inheriting the Maven plugin's listing.
The parent is required for model resolution and the core is a normal plugin dependency; users
configure `shardpilot-maven-plugin` to enable sharding.

### ShardPilot build parent

`io.github.sjacek:shardpilot-parent:0.3.1`, packaging `pom`; [source POM](../pom.xml).

> ShardPilot helps shorten CI feedback by splitting JVM test classes into deterministic, non-overlapping shards for parallel CI jobs. Gradle and Maven adapters share balancing based on historical test durations, stable hashing when timings are unavailable, YAML class manifests and standalone JSON and HTML reports for verifying class selections. This parent POM supplies shared dependency and build configuration for shardpilot-core and shardpilot-maven-plugin. Consumers configure the appropriate build-tool adapter; class-selection verification complements normal test result checks.

### ShardPilot core

`io.github.sjacek:shardpilot-core:0.3.1`, packaging `jar`; [source POM](../shardpilot-core/pom.xml).

> Shared Java core used by the ShardPilot Gradle and Maven adapters for deterministic JVM test sharding. Balances class families using historical test durations or stable hashing when timings are unavailable, reads and writes YAML class manifests, and verifies complete, non-overlapping class selections with standalone JSON and HTML reports. Contains the common planning and reporting logic without Gradle or Maven APIs; test execution and process settings belong to the build-tool adapters and native runners.

## Gradle Plugin Portal

| Field | Value |
| --- | --- |
| Display name | ShardPilot |
| Plugin ID | `io.github.sjacek.shardpilot` |
| Version | `0.3.1` |
| Implementation | `io.github.sjacek:shardpilot:0.3.1` |
| Description source | Root [build.gradle](../build.gradle), used by the [Gradle adapter](../shardpilot-gradle-plugin/build.gradle) |
| Tags | `testing`, `sharding`, `testng`, `spock`, `junit`, `ci` |
| Website | [sjacek/shardpilot](https://github.com/sjacek/shardpilot) |
| VCS | `https://github.com/sjacek/shardpilot.git` |

The Portal description and locally generated Gradle implementation/marker POM descriptions use the same text:

> ShardPilot helps shorten CI feedback for large JVM unit and integration test suites by splitting Gradle Test tasks into deterministic, non-overlapping class-level shards for parallel CI jobs. It balances class families using historical test durations, with stable hashing when timing data is unavailable. Works with existing JUnit Jupiter, Spock and TestNG tasks while preserving nested test families and configured test filters. Optional per-task controls set worker heap size, parallel forks and process recycling. Opt-in YAML class manifests and standalone JSON and HTML reports compare planned assignments with class selections collected from CI jobs. Supports Gradle Configuration Cache and any CI provider without a coordinator, replacement test runner or telemetry. Your CI launches every shard and collects the results; class-selection verification complements normal test result checks.

## Verification and publication

The live Plugin Portal generates its marker POM independently; the 0.3.0 marker contains coordinates
and the implementation dependency, without the local POM's display name, description or homepage.
The 0.3.1 homepage fix concerns the native Maven Central parent/core/adapter publications. It does not
promise to rename an existing MVNRepository entry or change the Portal-generated marker. See
[Gradle's explanation of marker metadata](https://discuss.gradle.org/t/missing-properties-in-pom-files-of-plugins-gradle-org-m2/52072).

The descriptions agree on deterministic class assignment, optional timing history, YAML and
JSON/HTML outputs, and CI-owned job scheduling. Framework support, Configuration Cache and optional
worker process controls are stated in the Gradle description. Native goals, Surefire/Failsafe and
parallel Maven reactor support are stated in the Maven description. Neither listing promises a
specific speedup, automatic CI jobs or that matching selections prove test methods passed.

Central requires project name, description and URL in the
[published POM metadata](https://central.sonatype.org/publish/requirements/#project-name-description-and-url).
The Gradle listing uses the plugin declaration's description; see the
[Portal publishing guide](https://plugins.gradle.org/docs/publish-plugin).
Validate generated metadata before release rather than editing generated POMs or descriptors.
See [local verification](testing.md#publication-artifact-checks) and
[Central publication setup](maven-central.md). Updating this checkout prepares the next release's
metadata; it does not update a live portal listing.
