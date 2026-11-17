# Complete selection and execution example

Prepared for **ShardPilot 0.6.0**; publication is pending. Smoke selects two of three
native JUnit tests, full selects all three, and `acceptance-affected-testdata` uses a
committed Git diff to protect a boundary probe. Each flow freezes its decision, runs
two workers and verifies native results together with compiled-file integrity.

See the [complete workflow guide](https://github.com/sjacek/shardpilot/blob/main/docs/selection-migration.md)
and [fresh packaged-consumer acceptance](https://github.com/sjacek/shardpilot/blob/main/docs/release-consumers.md).
The Gradle settings use a versioned plugin by default; explicit `example.pluginSourceDirectory`
selects a source-development build. Maven pins the same candidate version in its POM.

The release driver stages real publication artifacts or imports a completed registry audit.
It excludes cached ShardPilot binaries, keeps strict native verification, runs all three
profiles and checks both modified bytes and a wrong source revision. The broad affected
rule is an acceptance fixture; replace it with reviewed ownership in an application.

The policy, duration history and native sources match across Gradle and Maven. Test
selection remains inside the plugin; application users do not supply a selector callback.
