# C3: compiled artifact integrity

**Development feature. Not included in the published 0.5.0 release.** Build/install the current
development source using the [local development setup](https://github.com/sjacek/shardpilot/blob/main/README.md#local-plugin-development).

C3 seals the exact files produced by a compilation job and rejects missing, changed or unexpected
files before a native test worker starts. It is independent of sharding and selection. The same
`CompiledArtifacts` implementation is used by Gradle and Maven. It never compiles, repairs outputs,
executes Git, downloads dependencies or extracts archives.

## Trust and inventory contract

The producer and consumer independently configure:

- A full lowercase Git revision (40 or 64 hexadecimal characters), supplied from trusted CI metadata.
- A suite identity, such as `unit`, `integration` or `daily`.
- Existing, disjoint root-relative directories covering **all transferred runtime inputs**: classes,
  resources, module JARs and any frozen plans required by the worker.
- Optional required directories containing a nonempty file and required nonempty files.

The version-1 [manifest schema](schemas/compiled-artifacts-v1.schema.json) records those values,
every directory, every regular file's byte length and SHA-256. Empty directories are inventoried
too. File order and output bytes are deterministic; absolute producer paths and resource contents
are not retained. A manifest from another suite/revision or a different configured path policy fails.
Required paths are checked on both sealing and verification; an empty resource directory cannot
stand in for compiled test classes. Declare each critical class/resource/JAR as a required file
when its presence, rather than merely a nonempty directory, is important.

The consumer's expected revision must come from its own CI context, not the downloaded manifest.
C3 compares that value; it does not discover checkout HEAD or establish that a producer actually
compiled that revision. Hashes detect byte changes, not a compromised producer that can replace
both artifacts and the manifest. An optional expected manifest SHA-256 can bind the complete
manifest to **separately trusted** CI metadata. Downloading that hash alongside a mutable manifest
does not authenticate it. Protect the CI artifact channel and producer job.

Roots must exist and may not overlap, including case-insensitive overlaps. Paths use `/`, support
spaces and UTF-8 names, and reject traversal, absolute/drive paths, control characters and ambiguous
Windows names. Symbolic links, Windows junction ancestors, special files, duplicate hard-link aliases within the inventory and
case-colliding paths fail. Files are streamed with explicit byte, entry and depth ceilings; observable
changes during reading fail. Use an isolated workspace with exclusive ownership while sealing,
verifying and testing. This is not a sandbox against a process concurrently replacing filesystem
ancestors or modifying files after the final check.

Keep the manifest **outside every inventoried root**, for example under `build/shardpilot/` while
inventorying narrower `build/classes` and `build/resources` trees. A failed reseal removes an earlier
manifest before scanning; verification only reads and never manufactures a missing manifest.
Keep mutable native reports, actual-shard selections and other worker outputs outside these roots
too. When retaining frozen plans, give them a separate immutable directory rather than sealing a
parent directory into which a worker will later write its actual selection.

## Gradle Groovy DSL

Configure once, outside a deferred `TaskProvider.configure` action, as with the other per-Test DSLs:

```groovy
tasks.named('test', Test).get().shardPilot.compiledArtifacts {
    // revision defaults to the required -Pshardpilot.compiledRevision property.
    roots = ['build/classes', 'build/resources']
    requiredDirectories = ['build/classes/java/test']
    requiredFiles = ['build/resources/test/config.txt']
    // Optional: expectedManifestSha256 from separately trusted CI metadata.
}

// Explicit worker-only mode: retain your existing CI producer-disable policy.
def precompiled = providers.gradleProperty('precompiled').isPresent()
tasks.withType(JavaCompile).configureEach { enabled = !precompiled }
tasks.withType(ProcessResources).configureEach { enabled = !precompiled }
```

The example assumes those roots and `src/test/resources/config.txt` exist. Adapt requirements to
your project; omit genuinely absent roots, and include module JAR directories/generated inputs when
the test runtime uses them. Other language compilers, generators, JAR producers and dependency
preparation remain part of your own CI configuration. C3 does not disable arbitrary project tasks.

This registers `sealTestCompiledArtifacts` and `verifyTestCompiledArtifacts`. The latter is a Test
dependency, so even an up-to-date/cached/NO-SOURCE Test cannot bypass artifact validation. A second
check runs immediately before a worker starts, catching mutations by other explicitly scheduled tasks
after the first gate. Both checks rehash files. Neither standalone C3 task has producer dependencies.
Normal Test dependencies remain unchanged; worker jobs that must not compile still disable them.

Producer job (the two commands are intentionally separate):

```shell
./gradlew testClasses
./gradlew sealTestCompiledArtifacts -Pshardpilot.compiledRevision="$CI_COMMIT_SHA"
```

Retain the configured trees plus `build/shardpilot/test/compiled-artifacts.json`, then restore them
into an isolated worker workspace. The worker's independently trusted revision is supplied again:

```shell
./gradlew test -Pprecompiled -Pshardpilot.compiledRevision="$CI_COMMIT_SHA"
```

For artifact-only checks use `verifyTestCompiledArtifacts` directly; it never schedules Test or
compilation. Defaults are `baseDirectory = projectDirectory`, `suite = task.name` and
`manifestFile = build/shardpilot/TASK/compiled-artifacts.json`. Explicit setters override them.
Optional `-Pshardpilot.compiledManifestSha256=...` supplies the separately retained manifest digest.

## Gradle Kotlin DSL

The same contract and default task names apply:

```kotlin
import io.github.sjacek.shardpilot.ShardPilotExtension

tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>().compiledArtifacts {
    roots.set(listOf("build/classes", "build/resources"))
    requiredDirectories.set(listOf("build/classes/java/test"))
    requiredFiles.set(listOf("build/resources/test/config.txt"))
}
```

Without a Java plugin or Test task, register `SealCompiledArtifacts` and `VerifyCompiledArtifacts`
directly and configure their `options` (`revision`, `suite`, `roots` and other settings). Their default
manifest is `build/shardpilot/compiled-artifacts.json`. No automatic sealing or verification occurs
just by applying ShardPilot. These tasks are always untracked/noncacheable, including when a consumer
tries to enable broad task caching; restored cache metadata cannot replace the C3 checks.

## Maven configuration

Add this configuration to the development ShardPilot plugin declaration. Required identity properties
are supplied by the caller; use separate manifest paths for independent reactor projects/suites.

```xml
<configuration>
  <compiledSuite>unit</compiledSuite>
  <compiledRoots>
    <compiledRoot>target/classes</compiledRoot>
    <compiledRoot>target/test-classes</compiledRoot>
  </compiledRoots>
  <compiledRequiredDirectories>
    <compiledRequiredDirectory>target/test-classes</compiledRequiredDirectory>
  </compiledRequiredDirectories>
  <compiledRequiredFiles>
    <compiledRequiredFile>target/test-classes/config.txt</compiledRequiredFile>
  </compiledRequiredFiles>
</configuration>
```

Both goals are standalone, offline-capable and project-free, with no default phase, lifecycle fork
or dependency resolution. `compiledBaseDirectory` defaults to `${project.basedir}` and
`compiledManifestFile` to `${project.build.directory}/shardpilot/compiled-artifacts.json`; supply both
explicitly when invoking a fully qualified goal without a POM. `compiledRevision`, `compiledSuite`
and `compiledRoots` are required. POM configuration/CLI properties use the names below:

| Gradle option | Maven parameter/property (property prefix `shardpilot.`) |
| --- | --- |
| `baseDirectory` | `compiledBaseDirectory` |
| `manifestFile` | `compiledManifestFile` |
| `revision` | `compiledRevision` |
| `suite` | `compiledSuite` |
| `roots` | `compiledRoots` |
| `requiredDirectories` | `compiledRequiredDirectories` |
| `requiredFiles` | `compiledRequiredFiles` |
| `expectedManifestSha256` | `compiledManifestSha256` |

For the producer, explicitly compile first and then seal:

```shell
./mvnw test-compile shardpilot:seal-compiled-artifacts -Dshardpilot.compiledRevision="$CI_COMMIT_SHA"
```

On a restored worker, invoke the guard before the **direct native goals**, avoiding a lifecycle
that recompiles/repairs transferred inputs:

```shell
./mvnw shardpilot:verify-compiled-artifacts surefire:test -Dshardpilot.compiledRevision="$CI_COMMIT_SHA"
./mvnw shardpilot:verify-compiled-artifacts failsafe:integration-test failsafe:verify -Dshardpilot.compiledRevision="$CI_COMMIT_SHA"
```

Alternatively bind `verify-compiled-artifacts` explicitly to `process-test-classes` for ordinary
Surefire/Failsafe lifecycles. Maven stops before the runner if the guard fails. That binding does
not disable preceding lifecycle compilation and must not be mistaken for a precompiled-only worker.
Use a separate execution/suite/manifest for independent compilation cohorts.

## Resource limits and failures

Limits can be lowered, never raised above these hard ceilings:

| Gradle option | Maven parameter/property | Default ceiling |
| --- | --- | ---: |
| `maxFiles` | `compiledMaxFiles` | 100000 |
| `maxEntries` | `compiledMaxEntries` | 200000 |
| `maxDepth` | `compiledMaxDepth` | 64 |
| `maxFileBytes` | `compiledMaxFileBytes` | 1073741824 |
| `maxTotalBytes` | `compiledMaxTotalBytes` | 8589934592 |
| `maxManifestBytes` | `compiledMaxManifestBytes` | 33554432 |

At most 256 disjoint roots, JSON depth 32, 500000 JSON values and 128 characters per numeric token
are accepted. Long numeric tokens fail before constructing a potentially expensive decimal value;
the producer enforces the same allocation ceiling as the reader. Failures use fixed `C3` reason codes such as
`REVISION_MISMATCH`, `SUITE_MISMATCH`, `POLICY_MISMATCH`, `MANIFEST_DIGEST_MISMATCH`,
`FILE_SET_MISMATCH`, `DIRECTORY_SET_MISMATCH`, `FILE_CONTENT_MISMATCH`,
`REQUIRED_ARTIFACT_MISSING`, `UNSAFE_ARTIFACT_PATH`, `UNSAFE_ARTIFACT_ALIAS` and `RESOURCE_LIMIT`.
Messages omit private paths, parser excerpts and resource contents. Missing/unreadable manifests
fail rather than falling back to compilation or a success report.

C3 establishes input integrity, **not test execution or semantic correctness of bytecode**. Keep
native failures and [C2 execution-evidence gates](execution-evidence-contract.md) independently
blocking. No consumer service migration, new published release or performance measurement is implied.

## Regression coverage

Core tests cover deterministic manifests, wrong revisions/suites/policies, optional manifest digests,
same-length corruption, missing/extra files and directories, required outputs, limits, traversal,
hard-link aliases, Windows junction ancestors and Linux/macOS symlinks; a Linux FIFO control proves special files are not opened.
TestKit exercises both DSLs, native JVM blocking, a post-gate mutation, Configuration Cache reuse,
forced build caching, standalone tasks and absent inputs. Maven tests check generated descriptors,
project-free calls and real Invoker Surefire/Failsafe goals, including rejected revision/digest
invocations that must not launch another worker. See [testing](testing.md) for commands and scope.
