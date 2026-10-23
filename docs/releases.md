# Releases and upgrades

[Documentation index](README.md)

This checkout prepares **0.2.1**, with [release notes and migration examples](releases/0.2.1.md).
It supersedes the unpublished 0.2.0 candidate and includes the expanded Plugin Portal description.
Publication is still pending; the existing published version is
[0.1.0](https://plugins.gradle.org/plugin/io.github.sjacek.shardpilot/0.1.0).
The build uses `com.gradle.plugin-publish` 2.2.1 and produces plugin, source and Javadoc JARs,
Maven publication metadata and the `publishPlugins` task. Project/SCM URLs, tags and the tested
configuration-cache feature are configured in `build.gradle`.

The [publication workflow](../.github/workflows/publish.yml) submits a release automatically after
a matching stable version tag and a successful compatibility matrix. The maintainer controls
commits, tag creation, publisher credentials and release-environment protection rules.

## Versioning policy

Use `MAJOR.MINOR.PATCH` for immutable releases; the Plugin Portal does not accept `-SNAPSHOT` versions.
Local development can use an included build without a separately versioned source revision. While
the plugin is pre-1.0, clearly call out behavior changes even when they occur in a minor release. Keep compatible
bug fixes in patch releases; never replace the bytes of an existing published version.

Treat assignment changes as externally observable behavior. Changing the hash, family naming,
timing aggregation or tie-breaking can move classes between shards. Document that change and
require every job in a pipeline to use the same plugin version. Public extension/property changes
also need migration notes and updated Groovy/Kotlin examples.

## Review publication prerequisites

1. Verify public repository ownership, the `io.github.sjacek.shardpilot` namespace, authorship and
   copyright attribution. Review the [MIT license](../LICENSE) and [third-party notices](../THIRD_PARTY_NOTICES.md).
2. Review the configured Plugin Portal tooling and project/SCM metadata. Keep publication
   implementation separate from sharding behavior.
3. Store publication credentials in the host's protected secret mechanism, not in repository files,
   examples, test fixtures, Gradle arguments printed to logs or shared caches.
4. Configure the release environment below. The publication workflow reruns all eight
   [Gradle/JDK cells](testing.md#gradle-and-jdk-matrix) for the exact tagged commit; a local
   Wrapper-only run or a green matrix for another revision does not replace this gate.
5. Review tracked files and publication artifacts for private application configuration, customer
   data, secrets and unintended infrastructure dependencies.

The `maven-publish` plugin alone does not make a version available through the Plugin Portal.
Keep [local composite builds](../README.md#local-plugin-development) as a development option,
not as a substitute for verifying published artifact resolution.

## Configure GitHub publication

In the GitHub repository, open **Settings → Environments** and create `gradle-plugin-portal`.
Add these environment secrets using the publisher's Plugin Portal API key:

| Secret | Value |
| --- | --- |
| `GRADLE_PUBLISH_KEY` | Plugin Portal API key identifier. |
| `GRADLE_PUBLISH_SECRET` | Matching Plugin Portal API secret. |

Do not store values in repository files, workflow YAML, command-line arguments or logs. The
environment secrets are used only by the final publishing step; they are not passed to tag
validation or the reusable compatibility workflow. Missing/empty credentials fail before upload.

Restrict this environment to version tags such as `v*`, not arbitrary branches. Protect creation
and updates of release tags with a repository ruleset so only trusted maintainers can release
code. Tag creation authorizes publication of that tag's code, including its Gradle build logic.
Required environment reviewers can add a manual approval gate if desired; without reviewers,
the publishing job runs automatically after the matrix succeeds. See
[GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

The workflow has read-only repository permissions, checks out the triggering commit SHA at every
stage and disables shared Gradle caches and configuration cache during credentialed execution.
It does not create tags, change the changelog or create a GitHub Release.

## Publish from a version tag

1. Set `version` in `build.gradle` to a new release version: `0.2.1` for this candidate. Version `0.1.0`
   is already published and must not be overwritten. Do not derive the version from a tag or source revision.
2. Run local checks and `bash ./gradlew verifyReleaseTag -PreleaseTag=v0.2.1`. This offline guard
   accepts only `vMAJOR.MINOR.PATCH` with no leading zeroes, suffixes or build metadata, and checks
   equality with the evaluated `project.version`. Snapshots and prerelease tags are not released
   by this workflow.
3. Commit and push the intended release revision, including both workflow files and
   `gradle/release-verification.gradle`. The maintainer then creates and pushes the immutable
   tag `v0.2.1` on that revision. Future versions use the same `v` prefix and must match the build.
4. Follow **Actions → Publish Gradle plugin**. The job chain is tag validation → reusable eight-cell
   compatibility matrix → Portal validation (`--validate-only`) → publication. Any failed,
   cancelled or skipped prerequisite prevents publication. No separate tag-triggered matrix is
   launched by the compatibility workflow.
5. Wait for Plugin Portal approval when required, then verify normal plugin resolution without
   `includeBuild` or Maven Local. A successful upload job is not proof of Portal availability.

Only creation of a matching `v[0-9]*` tag starts the release path. Deleting or force-updating an
existing tag does not publish. A mismatched or malformed version fails before the matrix; do not
move or recreate a published tag. Concurrent runs for the same tag do not interrupt an in-progress
upload. A failed run may be rerun for the same commit after fixing external configuration, but
never try to overwrite artifacts already accepted by the Portal. This automation does not bypass
the [Portal's initial manual review](https://plugins.gradle.org/docs/publish-plugin#approval).

## Prepare a release candidate

Update `version` in `build.gradle`, then update version/status references in the root README,
[documentation index](README.md) and this guide. Group candidate entries under the version with
**publication pending** in [CHANGELOG.md](../CHANGELOG.md), leaving a fresh `Unreleased` section.
After successful publication and consumer resolution, replace the pending marker with the Portal
version link and actual creation date. Do not invent a date while publication is still pending.

Run `./gradlew clean check assemble` (or `gradlew.bat clean check assemble`). Inspect the plugin,
sources and Javadoc JARs under `build/libs/`. Verify the plugin descriptor and MIT license entry;
the Wrapper's Apache-licensed files belong to the repository and are not bundled as plugin code.

Exercise both Groovy and Kotlin consumer examples with the candidate. After publication and Portal
approval, verify resolution of the exact candidate through the Plugin Portal, without an included
build or Maven Local. Running TestKit/composite builds proves implementation behavior, not that
remote marker artifacts and metadata are correct.

The maintainer configures `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` in a protected environment,
or `gradle.publish.key` and `gradle.publish.secret` in the user's private Gradle properties. Never
commit credentials or provide them as command-line arguments. These local settings are an
alternative for manual publication; GitHub uses the release environment secrets described above.
The compatibility workflow has no publication credentials and never publishes artifacts.

Run `bash ./gradlew publishPlugins --validate-only` for Portal validation without uploading; this
task still requires publisher credentials. After maintainer approval, run `publishPlugins` without
`--validate-only` to submit the candidate. The first publication requires Portal approval before
consumers can resolve it. Follow the [official publishing instructions](https://plugins.gradle.org/docs/publish-plugin).
Only record the release after publication and consumer resolution succeed; then prepare the next
version and a fresh `Unreleased` section.

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
