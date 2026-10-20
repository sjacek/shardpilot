# Releases and upgrades

[Documentation index](README.md)

The current version is `0.1.0-SNAPSHOT`. No public release is recorded. The build produces plugin,
source and Javadoc JARs and Maven publication metadata, but it does **not** yet configure the Gradle
Plugin Publish plugin, a `publishPlugins` task or a remote publication repository.

This guide is a maintainer checklist, not permission to publish or a claim that publication is
already automated. The maintainer performs commits, tags, credential setup and publication.

## Versioning policy

Use `MAJOR.MINOR.PATCH` for immutable releases and `-SNAPSHOT` for development. While the plugin is
pre-1.0, clearly call out behavior changes even when they occur in a minor release. Keep compatible
bug fixes in patch releases; never replace the bytes of an existing published version.

Treat assignment changes as externally observable behavior. Changing the hash, family naming,
timing aggregation or tie-breaking can move classes between shards. Document that change and
require every job in a pipeline to use the same plugin version. Public extension/property changes
also need migration notes and updated Groovy/Kotlin examples.

## Prepare the first publication

1. Verify public repository ownership, the `io.github.sjacek.shardpilot` namespace, authorship and
   copyright attribution. Review the [MIT license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md).
2. Add and test Plugin Portal publication tooling and required project/SCM metadata. Keep the
   publication implementation separate from sharding behavior; do not document a task as available
   before it exists.
3. Store publication credentials in the host's protected secret mechanism, not in repository files,
   examples, test fixtures, Gradle arguments printed to logs or shared caches.
4. Run the intended Gradle/JDK/OS verification matrix. Advertise only combinations actually tested.
5. Review tracked files and publication artifacts for private application configuration, customer
   data, secrets and unintended infrastructure dependencies.

The `maven-publish` plugin alone does not make this project available through the Plugin Portal.
Keep local composite-build instructions until a real published version has been verified.

## Prepare a release candidate

Update `version` in `build.gradle`, then update version/status references in the root README,
[documentation index](README.md) and this guide. Move released entries from `Unreleased` in
[CHANGELOG.md](../CHANGELOG.md) to the actual version and actual release date. Do not invent a
publication date while the release is still pending.

Run `./gradlew clean check assemble` (or `gradlew.bat clean check assemble`). Inspect the plugin,
sources and Javadoc JARs under `build/libs/`. Verify the plugin descriptor and MIT license entry;
the Wrapper's Apache-licensed files belong to the repository and are not bundled as plugin code.

Exercise both Groovy and Kotlin consumer examples with the candidate. Once publication tooling
exists, verify resolution of the exact candidate through its intended distribution channel as
part of the release procedure. Running a composite build proves implementation behavior, not
that remote marker artifacts and metadata are correct.

After maintainer approval, publish through the configured channel and record the release. Prepare
the next development snapshot and a fresh `Unreleased` section. The details of signing, Portal
credentials and remote CI jobs must be added when that publication workflow is implemented.

## Upgrade the Gradle Wrapper

Upgrade the Wrapper independently of the plugin's public API version. Select a stable Gradle/JDK
combination using Gradle's official compatibility information and obtain the distribution checksum
from a trusted official source. Do not infer runtime compatibility from Java bytecode target 17.

Update the `wrapper` task's version and checksum in `build.gradle`, regenerate the Wrapper using
Gradle's Wrapper task and review the generated scripts, JAR and properties together. Ensure that
`gradle/wrapper/gradle-wrapper.properties` points at the same version/checksum as `build.gradle`.
Retain or update the Wrapper license and third-party notices as required by the new distribution.

Then run the complete checks with the new runtime, including TestKit and configuration-cache
reuse. Update the [verified scope](testing.md#verified-scope), README and changelog only after
successful verification. A Wrapper upgrade does not automatically establish support for older
Gradle releases or every JDK.

## Upgrade test dependencies or roll back

JUnit, Spock and Groovy versions used by tests live in `build.gradle` and TestKit fixture build
scripts. Update related versions consistently, rerun the fixtures and update the verification
notes. These are test dependencies, not libraries injected into consumer applications.

For a problematic release, pin consumers to the last known working version and rerun **all**
shards with that version and one consistent history snapshot. Do not mix old and new assignment
implementations within a pipeline or overwrite a published release to repair it. Describe the
problem and migration in a new changelog entry, then prepare a new version.
