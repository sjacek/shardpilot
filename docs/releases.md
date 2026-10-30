# Releases and upgrades

[Documentation index](README.md)

Version **[0.4.0](releases/0.4.0.md)** adds opt-in Gradle/Maven duration-history refresh with
candidate TSV and diagnostic JSON. See its [upgrade and verification notes](releases/0.4.0.md).
Existing sharding, YAML manifests and selection reports remain compatible with
[0.3.1](releases/0.3.1.md); the refresh task/goal requires explicit invocation.
The build uses `com.gradle.plugin-publish` 2.2.1 and produces plugin, source and Javadoc JARs,
Maven publication metadata and the `publishPlugins` task. Project/SCM URLs, tags and the tested
configuration-cache feature are configured in `shardpilot-gradle-plugin/build.gradle`.
Root `build.gradle` owns the shared Gradle version and release-tag guard; root `pom.xml` is the
independent Maven reactor entry point.

Review the [publication descriptions](publication-descriptions.md) for the complete Maven Central
and Gradle Plugin Portal text and its source metadata before creating a release tag.

The [publication workflow](../.github/workflows/publish.yml) submits the Gradle plugin automatically
after a matching stable version tag and a successful compatibility matrix. A manual run from that
same tag publishes Maven Central later, with its own complete compatibility gate. The maintainer controls
commits, tag creation, publisher credentials and release-environment protection rules.

## Keep required checks aligned with the matrix

The repository's active **main develop** ruleset applies to `main` and `develop`. The release
gate has 12 required contexts, emitted by GitHub Actions:

| Job contexts | Required JDKs | Count |
| --- | --- | --- |
| `Gradle 9.0.0 / JDK N` | 17, 21 | 2 |
| `Gradle 9.1.0 / JDK N` | 17, 21, 25 | 3 |
| `Gradle 9.8.2 / JDK N` | 17, 21, 25 | 3 |
| `Maven 3.9.16 / JDK N` | 17, 21, 25 | 3 |
| `Signed Maven release bundle (no upload)` | Fixed release-verification runtime | 1 |

`N` above is replaced with the literal JDK number in each required name. These are job names,
without the workflow title or `(push)` / `(pull_request)` event suffix shown in parts of the UI.
For the 9.8.2 update, replace only `Gradle 9.8.1 / JDK 17`, `21` and `25` with their 9.8.2 names.
Do not retain both sets as requirements: a new workflow emitting only 9.8.2 cannot satisfy an
obsolete 9.8.1 requirement and GitHub will show it as expected indefinitely.

When changing a matrix version, update the workflow, `PublicationWorkflowTest`, documentation
and required contexts together. Retain all other requirements: strict up-to-date checks, GitHub
Actions as the expected source, pull requests, verified signatures and resolved review threads.
The same eight Gradle cells are used by the reusable release workflow; signed-bundle and Maven
jobs remain required as well. Coordinate the live rename with the workflow rollout:

1. Push the branch containing the updated matrix and wait for its new 9.8.2 status names to appear.
2. In **Settings → Rules → Rulesets → main develop**, replace just the three 9.8.1 requirements
   with the corresponding 9.8.2 names, retaining all 12 required contexts and their source.
3. Re-read the live ruleset and compare all names with the branch's actual reports before merge.
   Older open PRs may need to pick up the new workflow; their old statuses cannot satisfy the
   renamed requirements. Keep the same revision/history cohort for release checks.

While the changed workflow is only local, the remote ruleset still describes the deployed
workflow. Local tests cannot establish live CI success or update administrative settings.

The CLI can inspect the configured requirements without changing them:

```shell
gh api repos/sjacek/shardpilot/rulesets/25711406
```

`PublicationWorkflowTest` guards the matrix and exact job-name templates; a maintainer still
verifies remote ruleset state because local unit tests have no administrative API side effects.

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
4. Configure the Gradle release environment below before pushing the tag; Central credentials
   can be prepared before the later manual Maven run. Each publication reruns all eight
   [Gradle/JDK cells](testing.md#gradle-and-jdk-matrix), three Maven/JDK jobs and the signed-bundle check for the exact tagged commit; a local
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

Before the later manual Maven publication, create **`maven-central`** with `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE`. Follow the
[Central token, GPG key and secret setup](maven-central.md). The existing `io.github.sjacek`
namespace was already verified for Asyncwright; ShardPilot uses the same namespace.

Do not store values in repository files, workflow YAML, command-line arguments or logs. The
environment secrets are used only by the final publishing step; they are not passed to tag
validation or the reusable compatibility workflow. Missing/empty credentials fail before upload.

Restrict both environments to version tags such as `v*`, not arbitrary branches. Protect creation
and updates of release tags with a repository ruleset so only trusted maintainers can release
code. Tag creation authorizes Gradle publication; manual dispatch from that tag authorizes Maven
publication. Both execute that tag's build logic.
Required environment reviewers can add a manual approval gate if desired; without reviewers,
the selected publishing job runs after its trigger and successful matrix. See
[GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

The workflow has read-only repository permissions and checks out the triggering commit SHA at every
stage. Credentialed execution uses no shared build caches; Gradle also disables Configuration Cache.
It does not create tags, change the changelog or create a GitHub Release.

![Required checks and separate publication environments](diagrams/publication.svg)

## Publish from a version tag

1. Set `version` in root `build.gradle` to the new release version, currently `0.4.0`.
   Align root `pom.xml`, core/plugin module parent versions and pinned example POM versions.
   `PublicationWorkflowTest` checks Maven reactor versions against the evaluated Gradle version.
   Published versions must not be overwritten. Do not derive the version from a tag or source revision.
2. Run local checks and `bash ./gradlew verifyReleaseTag -PreleaseTag=v0.4.0`. This offline guard
   accepts only `vMAJOR.MINOR.PATCH` with no leading zeroes, suffixes or build metadata, and checks
   equality with the evaluated `project.version`. Snapshots and prerelease tags are not released
   by this workflow.
3. Commit and push the intended release revision, including both workflow files and
   `gradle/release-verification.gradle`. The maintainer then creates and pushes the immutable
   tag `v0.4.0` on that revision. Future versions use the same `v` prefix and must match the build.
4. Follow **Actions → Publish ShardPilot**, run **Gradle Plugin Portal / v0.4.0**. The job chain is tag validation → reusable
   compatibility workflow (eight Gradle cells, three Maven/JDK jobs and signed-bundle verification)
   → Gradle publication. Gradle performs Portal validation (`--validate-only`)
   before upload; the Maven publisher is skipped in this tag-push run. Any failed,
   cancelled or skipped prerequisite prevents publication. No separate tag-triggered matrix is
   launched by the compatibility workflow.
5. Wait for Plugin Portal approval when required, then verify normal plugin resolution without
   `includeBuild` or Maven Local. A successful upload job is not proof of Portal availability.
6. When ready, publish Maven from the same tag using the separate manual path below.

Only creation of a matching `v[0-9]*` tag starts automatic Gradle publication. Deleting or force-updating an
existing tag does not publish. A mismatched or malformed version fails before the matrix; do not
move or recreate a published tag. Concurrent runs for the same tag do not interrupt an in-progress
upload. A failed run may be rerun for the same commit after fixing external configuration, but
never try to overwrite artifacts already accepted by the Portal. This automation does not bypass
the [Portal's initial manual review](https://plugins.gradle.org/docs/publish-plugin#approval).

## Publish Maven later from the same tag

The `workflow_dispatch` path publishes only Maven Central. Configure its environment when ready,
then run **Publish ShardPilot** with the existing release tag as the workflow ref. From GitHub CLI:

```shell
gh workflow run publish.yml --repo sjacek/shardpilot --ref v0.4.0
```

Use the CLI command to select the tag explicitly. A manual run from `main` fails before checkout:
branch refs are rejected even if a branch has a version-like name. The workflow must already be
on the default branch and included in the release tag. See the
[GitHub CLI ref option](https://cli.github.com/manual/gh_workflow_run).
There is no separate version/tag input that could disagree with the selected checkout.

Follow **Maven Central / v0.4.0**. This run checks out the tag's exact commit SHA, revalidates the
version and reruns all 12 compatibility checks. It then signs the parent/core/adapter cohort,
uploads it and waits for Central publication. The Gradle publisher is skipped. Verify ordinary
Maven resolution in a fresh consumer without local candidate artifacts afterwards.

The portals remain independent. A manual Maven publication does not rerun Gradle publication.
On failure, inspect remote status. For Central, resume an accepted deployment with the optional
`maven_deployment_id` input; the helper polls its status without uploading again. An upload-run
rerun without that ID is refused. Never rerun a successful publication to overwrite immutable artifacts. See
[partial-publication recovery](maven-central.md#release-and-recover-a-partial-publication).
This recovery path requires a future tag containing the updated workflow; existing published
tags, including `v0.3.1`, remain unchanged.

## Prepare a release candidate

Update `version` in `build.gradle`, then update version/status references in the root README,
[documentation index](README.md) and this guide. Group entries under the version heading in
[CHANGELOG.md](../CHANGELOG.md), leaving a fresh `Unreleased` section.
After successful publication and consumer resolution, add the Portal version link and actual
creation date. Use the date recorded by the Portal; omit it until known.

Run `./gradlew clean check assemble` (or `gradlew.bat clean check assemble`). Inspect the plugin,
sources and Javadoc JARs under `shardpilot-gradle-plugin/build/libs/`. Verify the plugin descriptor and MIT license entry;
the Wrapper's Apache-licensed files belong to the repository and are not bundled as plugin code.

Exercise both Groovy and Kotlin consumer examples with the candidate. After publication and Portal
approval, verify resolution of the exact candidate through the Plugin Portal, without an included
build or Maven Local. Running TestKit/composite builds proves implementation behavior, not that
remote marker artifacts and metadata are correct.

## Distribution artifacts

Source-module names and distribution coordinates serve different purposes. The Gradle adapter's
module move must not rename its published `shardpilot` artifact or plugin marker.

| Distribution | Coordinates at 0.4.0 | Local output / dependency boundary |
| --- | --- | --- |
| Gradle implementation | `io.github.sjacek:shardpilot:0.4.0` | `shardpilot-gradle-plugin/build/libs/shardpilot-0.4.0.jar`; core classes bundled, SnakeYAML resolved at runtime. |
| Gradle marker | `io.github.sjacek.shardpilot:io.github.sjacek.shardpilot.gradle.plugin:0.4.0` | Generated marker POM depends on the unchanged implementation coordinates. |
| Maven parent | `io.github.sjacek:shardpilot-parent:0.4.0` | Root `pom.xml`; parent POM required to resolve the Maven modules' models. |
| Maven core | `io.github.sjacek:shardpilot-core:0.4.0` | `shardpilot-core/target/shardpilot-core-0.4.0.jar`; shared logic and normal SnakeYAML dependency. |
| Maven adapter | `io.github.sjacek:shardpilot-maven-plugin:0.4.0` | `shardpilot-maven-plugin/target/shardpilot-maven-plugin-0.4.0.jar`; generated native plugin descriptor and core dependency. |

Inspect generated Gradle metadata under `shardpilot-gradle-plugin/build/publications/` using
`generatePomFileForPluginMavenPublication`, `generatePomFileForShardpilotPluginMarkerMavenPublication`
and `generateMetadataFileForPluginMavenPublication`. These metadata tasks do not publish. Check
that the implementation POM/module retains `shardpilot`, the marker targets it and no separate
`shardpilot-core` publication is required for Gradle consumers.

The Maven reactor has its own build/release artifacts. Run `./mvnw -B -ntp verify` and keep the
root POM, core/plugin parent versions and Gradle release version aligned. The required reusable
workflow includes Maven/JDK checks as well as the eight Gradle cells. Maven Wrapper checksum and
native filters/Invoker fixtures are verified without publisher credentials.

The Gradle publishing job bundles core classes under the original `io.github.sjacek:shardpilot`
coordinates and unchanged plugin marker. The separate Maven job publishes the parent, core and
adapter together with source/Javadoc JARs and PGP signatures. An explicit `central-release` profile
owns Maven signing and Central configuration; ordinary builds never activate it. Even with the
profile active, `central.skipUpload=true` defaults to producing a local bundle. Only the credentialed
job explicitly enables upload. `./mvnw install -DskipITs` remains local installation.

Run `bash scripts/verify-maven-release.sh` before release changes are handed off. This credential-free
check uses a disposable key, builds the same profile and validates the complete bundle plus all
nine signatures. Its test-key bundle must never be published. See the
[full setup and local verification guide](maven-central.md).

## Portal validation

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

JUnit, Spock and Groovy versions used by tests live in module build files, Maven POMs and TestKit
fixture build scripts. Update related versions consistently, rerun the fixtures and update the verification
notes. These are test dependencies, not libraries injected into consumer applications.

For a problematic release, pin consumers to the last known working version and rerun **all**
shards with that version and one consistent history snapshot. Do not mix old and new assignment
implementations within a pipeline or overwrite a published release to repair it. Describe the
problem and migration in a new changelog entry, then prepare a new version.
