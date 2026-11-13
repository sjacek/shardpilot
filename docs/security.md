# Security and trust boundaries

[Security policy](../SECURITY.md) | [Review follow-up](security-review-2026-11-12.md) |
[CI integration](ci.md) | [Release guide](releases.md)

This guide describes implementation boundaries and deployment responsibilities. It is not a
certification that a build, artifact or consumer project is safe. Report suspected vulnerabilities
through the private channel in the security policy.

## Build-process permissions

The Gradle adapter runs in the Gradle build JVM; the Maven adapter runs in Maven's plugin realm.
They can access resources available to those processes. Neither adapter is a security boundary
against a malicious build script, dependency, plugin, test fixture or compromised runner.

Consumers should:

- Review plugin coordinates, versions and their source before adoption; use a fixed version in
  every job of one test cohort, with independently verified artifact provenance where available.
- Run untrusted pull requests and initial evaluations without production or publication secrets.
- Restrict runner filesystem access and credentials to what each job needs; isolate shard outputs
  and caches so one node cannot silently replace another node's evidence.
- Treat YAML manifests, timing TSV, JUnit XML and generated JSON/HTML as project metadata that may
  reveal package names, class names, project paths or user-controlled text. Limit artifact access
  and retention and avoid adding confidential material to fixtures.
- Require native test outcomes, complete shard collection and selection verification separately.
  An empty selection, unavailable report or matching class manifest is not proof of successful tests.

## Metadata and report boundaries

The following controls exist in the reference implementation; their presence is not proof of
resistance to every malformed input or resource-exhaustion attack:

| Boundary | Existing control | Evidence |
| --- | --- | --- |
| JUnit XML timing import | Disables DTDs and external entities; supplies a resolver that refuses external entities and avoids echoing parser payloads. | `JUnitXmlTimings` and `JUnitXmlTimingsTest` in `shardpilot-core`. |
| YAML class manifests | Rejects duplicate keys, collection aliases, unsupported schemas, incorrect field types and duplicate class identities. | `ClassManifest` and `ClassManifestTest` in `shardpilot-core`. |
| HTML presentation | Escapes artifact-derived text and embeds its styles without scripts or external resources. | `ShardCoverageHtml` and `ShardCoverageHtmlTest` in `shardpilot-core`. |
| Shard evidence | Validates class ownership and cohort consistency; missing or inconsistent evidence is not manufactured by aggregation. | [Manifest contract](shard-verification.md) and core coverage/manifest tests. |

The history importer reads timing metadata, not a trustworthy record of all successful test
executions. Native runners own disabled tests, dynamic discovery, parameterized invocations,
retries and test failures. See the [history contract](history-refresh.md) and
[selection contract](test-selection.md).

## Network and telemetry claims

ShardPilot is designed without a telemetry uploader, coordinator service or automatic remote
history collection. This is an implementation/design statement, not a completed dynamic network
audit. Gradle, Maven, their plugins, TestKit consumers and the consumer's tests may legitimately
download distributions/dependencies or contact other services.

A dynamic check must separate dependency warm-up from an isolated execution phase, document
allowed destinations, record attempted connections and test the actual distributed artifacts.
Do not attribute every build connection to ShardPilot or infer absence of all network activity
from a source search. The [published 0.4.0 artifact audit](published-artifact-audit.md) now records
bounded Linux-container observations for the authored Gradle/Jupiter and Maven Surefire/Failsafe
consumers, including a blocked positive control. It does not cover every code path or consumer.

## Publication boundaries

The checked-in workflows use read-only repository permissions and disable persisted checkout
credentials. Compatibility jobs do not receive publication environment secrets. Publishing jobs
use separate Gradle Plugin Portal and Maven Central environments; caches are disabled for the
credentialed Gradle publication. The Maven release profile signs artifacts, and compatibility
includes an upload-disabled release-bundle/signature check with a disposable key.

Compatibility warmup shares allowlisted dependency/distribution snapshots, not task results or
locally built ShardPilot artifacts. Only the default branch writes persistent snapshots; PRs use
their own same-run artifacts, and every matrix job receives a separate writable copy. Imports
reject links and unexpected paths. Neither credentialed publisher restores these snapshots.
See [dependency cache and warmup](testing.md#dependency-cache-and-sequential-warmup).

Those are repository-defined controls. Environment approvals, tag protection, account security,
private reporting and other GitHub settings require separate maintainer configuration. A YAML
environment name does not prove that reviewers or tag restrictions are enabled. Follow the
[publication prerequisites](releases.md#review-publication-prerequisites) and
[Central instructions](maven-central.md).

Pinned wrapper distribution checksums do not replace dependency verification, an SBOM or CVE
analysis of the artifacts consumers actually resolve. The Gradle distribution bundles the core
classes; Maven resolves the core separately and has its own runtime dependency graph. Assess
those graphs separately from provided build-tool APIs, test libraries and producer tooling.

The [SBOM and vulnerability workflow](dependency-security.md) inventories those two distribution
graphs separately, adds distinct build/test/provided reports, and requires every scope to pass
before compatibility/publication. It binds local
candidate JAR hashes and records scanner/database identities. The separate
[build dependency integrity gate](dependency-verification.md) pins Actions commits and verifies
Gradle/TestKit and Maven dependency bytes, including restored caches. Verification of actual
previously published registry bytes has separate [0.4.0 integrity/signature evidence](published-artifact-audit.md).
That check does not retroactively scan all published versions or prove reproducible binary builds.

## Maintainer setup for private reports

In the repository's **Settings**, open **Security and quality > Advanced Security** and enable
**Private vulnerability reporting**, following [GitHub's configuration guide](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository).
Check that an ordinary signed-in reporter can see **Report a vulnerability** on the
[advisories page](https://github.com/sjacek/shardpilot/security/advisories). Do not submit a real
vulnerability or disclose confidential information merely to test the form.

Adding `SECURITY.md` does not change this setting. The 12 November follow-up did not change
repository settings or establish that private reporting was enabled. Until the channel is
available, the security policy allows a public request for a private contact with no vulnerability
details, consistent with [GitHub's reporter guidance](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).

On **13 November 2026**, the repository's private-reporting setting was found disabled, enabled,
then checked through `GET /repos/sjacek/shardpilot/private-vulnerability-reporting`, which returned
`{"enabled":true}`. This confirms the repository setting; it does not claim a submitted report
or a form test from another user's account. Keep the reporting link in `SECURITY.md` available.
