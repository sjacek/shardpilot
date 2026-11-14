# Producer dependency remediation — 13 November 2026

[Dependency scans](dependency-security.md) | [Dependency integrity](dependency-verification.md) | [Security review](security-review-2026-11-12.md)

The extended inventories exposed vulnerable producer tools and host-provided APIs that were
outside the original runtime scans. This follow-up updates those selected dependency graphs,
improves SDK identification and repairs the cache warmup failure observed in hosted CI.
The existing high-severity gate, inclusion of unfixed findings and complete report retention remain.

For the final hosted verdict, see [hosted confirmation](#hosted-confirmation-13-november-2026).
Earlier sections retain the initial failing scopes and remediation sequence.

## Scoped updates

| Boundary | Change |
| --- | --- |
| Gradle generator classpath | Jackson BOM 2.22.4, keeping CycloneDX Gradle plugin 3.5.1 and its supported API. |
| Gradle download-only fixtures | Explicit Jackson core/databind/YAML constraints at 2.22.4. This custom configuration has no Java usage attributes, so its selected libraries are constrained directly. The Java TestKit warmup fixture also aligns Jackson through the BOM. |
| Maven provided/test graph | Guava 33.7.2-jre through project dependency management, retaining the existing Maven API baseline. |
| Maven signing plugin | Bouncy Castle provider, OpenPGP and utility modules 1.87; Plexus Utils 3.6.2. |
| Maven SBOM generator | CycloneDX core 11.0.1, fixing the old core's validation issue, plus Jackson 2.22.4. Native generation/validation is exercised with plugin 2.9.3; this does not claim compatibility with every feature of that upstream plugin. |
| Maven Central publisher | Jackson 2.22.4, Guava 33.7.2-jre, HttpClient 5.6.5, HttpCore/HTTP2 5.4.4 and Plexus Utils 3.6.2. |
| Other producer plugins | Scoped BeanUtils 1.11.0, Plexus Utils 3.6.2/4.0.3, Plexus Archiver 4.15.0, jsoup 1.23.2, Commons Lang 3.18.0 and Snappy 0.5 updates where the affected plugin realms select them. |

Project dependency management does not manage plugin dependency realms, so Maven plugin updates
are explicit under plugin management and merge into the existing profile/plugin declarations.
No third-party library source or JAR is patched. Plugins already at their latest applicable stable
release retain that version while their affected libraries are updated in their own realms.

These are producer changes. Guava selection updates compilation/tests and the provided inventory;
it does not replace libraries in a consumer's Maven installation. Runtime implementation sources
are unchanged, and the final runtime inventories remain separately bound to the candidate JARs.

## Reviewed Gradle SDK identities

`security/gradle-sdk-artifacts.json` records thirteen SDK libraries, their Maven coordinates,
canonical Central source URLs and exact SHA-256 values. During preparation the selected SDK file
must match those bytes and the registry filename must match its declared artifact/version. Its
actual library version is retained; the supplying Gradle version is a separate provenance property.

The thirteen identities were checked against official Maven Central artifacts/sidecars, rather
than inferred solely from filenames. CI performs no remote identity lookup. Unknown third-party
SDK libraries, changed bytes, ambiguous entries or inconsistent coordinates fail preparation.
Generated Gradle API/TestKit JARs and the internal installation beacon remain explicitly identified
as Gradle distribution files. Their bundled classes are not decomposed into independently verified
Maven modules, so their generic identities still have limited advisory coverage.

After a Wrapper upgrade:

1. Run `securityInventories` and inspect the SDK files in the native `inputs.json` reports.
2. Verify each new third-party file's exact bytes against its official Maven artifact, and review
   its group/artifact/version/source before updating the registry. A matching filename alone is insufficient.
3. Rerun the SDK identity tests, regenerate the scoped SBOMs and scan all four Gradle scopes.
4. Retain the generated/internal Gradle files' explicit limitation; do not invent Maven coordinates
   or label an unknown third-party library as an opaque Gradle file to bypass review.

## Cache warmup failure in the previous hosted run

[Run 43396389622](https://github.com/sjacek/shardpilot/actions/runs/43396389622), head `bb712b0`, failed
in Maven dependency warmup before either security job or compatibility matrix executed. The signed
release-bundle job passed. This was a prerequisite failure, not evidence of failing plugin tests.

`dependency:go-offline -Psecurity-sbom` resolved the just-installed reactor core through the local
repository. The external checksum baseline correctly had no versioned checksum for that local
`io.github.sjacek:shardpilot-core:jar:0.4.0` candidate, so the verifier rejected it.

The helper now explicitly requests an invocation-bound candidate environment for that root offline
resolution, as it already does for native Invoker consumers. Only the five installed parent/core/
adapter POM/JAR files acquire disposable checksums. The external baseline still rejects versioned
ShardPilot candidates, local verification remains enabled, and candidate artifacts remain excluded
from exported download snapshots. A regression test checks that this environment reaches Maven
before dependency resolution and native failures propagate unchanged. The original failing
`dependency:go-offline -Psecurity-sbom` goal also passed locally with checksum enforcement enabled
and the invocation-bound candidate environment.

## Local validation and current boundary

- `check assemble` passed with **346 Gradle tests**, zero failures/errors/skips.
- Maven `verify` passed with **302 unit tests**, **17 Invoker fixtures** and **two runnable examples**.
- All **88 Linux/Python 3.12 script tests** passed, including the new SDK mapping and
  candidate-bound warmup cases.
- Signed release verification with a disposable identity passed for **nine artifacts and nine
  signatures**, with upload disabled. This exercises the patched signing, archive, documentation
  and Central-bundling paths; it does not publish a new release or exercise every upstream plugin goal.
- **15 new Gradle** and **82 new Maven** checksum entries were reviewed against official Central
  sidecars or artifact bytes: **89 unique artifacts** across the two baselines. All prior 363
  Gradle and 902 Maven entries remained unchanged. The SDK registry has thirteen separately checked identities.
- Action pins and scan policy remain enforced; there are no vulnerability exclusions or VEX exceptions.

The recorded Grype 0.121.3 database is `v6.1.10`, built `2026-11-13T06:20:21Z`. The four Gradle
scope scans report no matches. Maven runtime, test and provided scopes report no matches.
The Maven build inventory still reports **two High and two Medium matches** from Maven Site's
Jetty 9.4.58 dependency family. It has no remaining Critical matches in this scan.

### Initial Jetty blocker (before the explicit tooling boundary)

Maven Site 3.22.0 still uses Jetty 9.4. The public Maven Central metadata ends that branch at
9.4.58.v20250814; the patched 9.4 releases referenced by the advisories are not available there.
The latest public Jetty 10/11 releases also matched the same High advisories in metadata-only
probes. Public maintained Jetty 12 releases require a different API integration and are not a
compatible dependency-only replacement for this Site plugin.

At this stage, affected matches remained visible and **the Maven gate remained blocked**. There was no risk
acceptance, suppression or threshold change. Resolving this boundary requires an upstream Site
integration with maintained Jetty, a supported patched dependency source, or a separately reviewed
change to the supported producer tooling/boundary. A claim that CI is fully green would be incorrect.

### Hosted follow-up and independent test scheduling

[Run 43404999831](https://github.com/sjacek/shardpilot/actions/runs/43404999831), commit `1763191`,
confirmed successful download warmup, signed-bundle verification and all four Gradle scans.
The complete Maven JSON reports confirmed the same two High and two Medium Jetty matches.
That workflow still short-circuited all eleven matrix cells at their security prerequisite;
it did not execute their native tests.

The subsequent workflow change makes warmup the shared prerequisite of tests and scans. Native
tests then run in parallel with security, while both distribution scan contexts remain separate
requirements for merging and the reusable release workflow still blocks publication on any failure.
The twelve existing matrix/signing contexts retain their names; two explicit security contexts
bring the main ruleset to fourteen required checks. See [required contexts](releases.md#keep-required-checks-aligned-with-the-matrix).

The native Maven 3.10.0 effective model identifies `default-site`/`site` and
`default-deploy`/`site-deploy` bindings in all three reactor projects. CI runs the clean/default
lifecycles and direct verification goals, not the site lifecycle. Removing the locally managed Site
version would restore Maven's older default instead of removing this realm. The pin and full scan
therefore remain. A sanitized `tooling-model.json` now retains effective build versus managed-only
plugin identities and their bindings, with a `site-lifecycle-only` label where appropriate.
Labels do not filter SBOM membership or change the security gate, and are not execution attestations.

Local validation of that follow-up passed **17 workflow tests**, **90 Python script tests**,
Actionlint and the dependency integrity policy. Native Maven inventory generation and report
assembly passed for all three reactor projects; the sanitized model is retained and hash-bound
through the build inventory. Rescanning confirmed that Site and Jetty remain present, with the
same two High and two Medium matches and an intentionally failing security gate. The live main
ruleset was reread after the approved update: fourteen checks are required, all twelve previous
contexts are retained, and every other rule is unchanged.

At this stage, the parallel scheduling and explanatory-report changes still required a push
before a new hosted cohort could be verified. Real-suite performance measurement remains deferred.

## Private reporting

GitHub Private Vulnerability Reporting was found disabled, enabled on 13 November 2026 and then
confirmed with the repository API returning `{"enabled":true}`. This verifies the setting, not a
report submission from another user's account. See [maintainer setup](security.md#maintainer-setup-for-private-reports).

## Reviewed producer tooling scope

The subsequent reviewed policy is `security/maven-tooling-scope.json`: eighteen supported plugin
coordinates are blocking, and only Maven Site is inherited-only. Selection retains every component
owned by a blocking realm, including libraries shared with Site. Native Maven lifecycle controls
reject Site execution and unknown effective/direct plugin coordinates. This boundary supersedes
the earlier all-realms blocking behavior; it does not fix or suppress Jetty's advisories.

`build-full` retains the complete original tooling inventory and all findings. Its native scanner
exit code and evidence are validated just like blocking reports. Findings alone are diagnostic;
missing, malformed, stale or contradictory evidence still fails the aggregate. The four original
runtime/build/test/provided scopes remain blocking, with the same High threshold and no VEX/package
filters. The two required distribution job names and the fourteen required main checks are unchanged.

The authored startup guard and its verified compile API JARs are included in build evidence. Native
offline controls cover a passing build, Site phase/prefix/coordinate/default-goal/environment forms,
active profiles and unknown build/managed/report plugins. Their report is bound to the exact guard
JAR and probe-source checksum. See the [scope policy and local commands](dependency-security.md#explicit-maven-tooling-boundary).

Local validation passed 97 Python script tests, the workflow structural tests, full Maven
verification (302 unit tests, 17 Invoker cases and two examples), and fourteen offline native
guard controls. The final guard also passed the repeated 302-unit-test run and signed-bundle
verification for nine artifacts/signatures with upload disabled. Native inventories and Grype
scans passed the four blocking Maven scopes; `build-full` retained the same two High and two
Medium Jetty matches with an aggregate passing gate. These were local results, before the hosted confirmation recorded below.

References: [CycloneDX core validation advisory](https://github.com/advisories/GHSA-6fhj-vr9j-g45r),
[Bouncy Castle advisory](https://github.com/advisories/GHSA-9pwp-9qqc-pr26),
[Maven plugin dependency configuration](https://maven.apache.org/guides/mini/guide-configuring-plugins.html#Using_the_.3Cdependencies.3E_Tag),
[Jetty request-smuggling advisory](https://github.com/advisories/GHSA-355h-qmc2-wpwf),
[Jetty authentication advisory](https://github.com/advisories/GHSA-2fvj-hgj9-j2gr),
[Jetty security reports](https://jetty.org/security.html),
[public Jetty version metadata](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-server/maven-metadata.xml).

## Hosted confirmation: 13 November 2026

[Compatibility run 43418902994](https://github.com/sjacek/shardpilot/actions/runs/43418902994)
completed successfully for source **`ac2d28f745051886f2850094486f7bb8f008ba80`**, from
11:40:12 to 11:50:30 UTC. All fifteen jobs passed: warmup, eight Gradle/JDK combinations,
three Maven/JDK combinations, upload-disabled signed-bundle verification and the two security jobs.
Matrix cells ran independently of the scans after warmup. This is source CI with project version
0.4.0, not a registry audit or verification of the later 0.5.0 preparation changes.

| Maven report | Retained findings | Gate role / result |
| --- | --- | --- |
| `runtime` | None reported | Blocking / passed |
| `build` | None reported | Blocking / passed |
| `test` | None reported | Blocking / passed |
| `provided` | None reported | Blocking / passed |
| `build-full` | **2 High, 2 Medium** | Diagnostic findings, required valid evidence; native scanner exit 2 retained |

The full report retains `GHSA-355h-qmc2-wpwf`, `GHSA-2fvj-hgj9-j2gr`,
`GHSA-qh8g-58pp-2wxh` and `GHSA-7p3p-8qv8-m2vh` in the Jetty 9.4.58.v20250814 family.
Its report verdict stays false; the aggregate passes because findings in this complete inherited
realm report are diagnostic under the explicit policy. Operational, stale or contradictory evidence
still blocks. Grype **0.121.3**, database schema **v6.1.10**, built **2026-11-13T06:20:21Z**,
was used with the unchanged High cutoff, including unfixed matches and no VEX/package filters.
The four Gradle blocking scope reports also passed.

The retained native guard report passed **fourteen controls**: one valid build and thirteen expected
rejections covering Site phase/prefix/coordinate/version/default-goal/environment forms, Site bindings,
active profiles and unclassified direct/build/managed/report plugins. Raw logs confirm the actual
startup guard rejected each negative case, rather than a missing offline dependency.
The report binds guard JAR SHA-256
`b2fa97e33de12ef1d967c7ed7aa56c3937a305c5df1d5e3a643b2b547bd015a3`
and probe-source SHA-256
`8d66990dfd98fd947f79e1059bdf9a37ea87fb4078d14496de9fb5efdbadc2bc`;
the guard hash matches the JAR retained in the build inventory. The bundle check retained nine
artifacts, nine signatures and 54 bundle entries without uploading.

The main ruleset keeps fourteen required contexts, including both security jobs. This boundary
makes the supported tooling scope explicit; it does not remediate inherited Jetty or allow Site.
Future plugin/lifecycle changes require classification and native guard review before use.
At that stage, candidate 0.5.0 still required fresh CI after the version/example edits.

## Published-release follow-up: 13 November 2026

The later [main CI run 43435082883](https://github.com/sjacek/shardpilot/actions/runs/43435082883)
passed all fifteen jobs for `dacbfbdb07e61f37ffa524fb286a97483c6c93e9`, the signed `v0.5.0` release.
Both registries then published 0.5.0. Its [post-publication audit](published-artifact-audit.md#published-050-baseline-13-november-2026)
passed registry/signature and isolated execution checks, supporting the default baseline update.
This does not remediate the inherited Site/Jetty findings recorded above. Historical 0.4.0 evidence
remains separate, and real-suite performance measurement remains deferred.
