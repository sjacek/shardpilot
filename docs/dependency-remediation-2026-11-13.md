# Producer dependency remediation — 13 November 2026

[Dependency scans](dependency-security.md) | [Dependency integrity](dependency-verification.md) | [Security review](security-review-2026-11-12.md)

The extended inventories exposed vulnerable producer tools and host-provided APIs that were
outside the original runtime scans. This follow-up updates those selected dependency graphs,
improves SDK identification and repairs the cache warmup failure observed in hosted CI.
The existing high-severity gate, inclusion of unfixed findings and complete report retention remain.

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

### Remaining Jetty issue

Maven Site 3.22.0 still uses Jetty 9.4. The public Maven Central metadata ends that branch at
9.4.58.v20250814; the patched 9.4 releases referenced by the advisories are not available there.
The latest public Jetty 10/11 releases also matched the same High advisories in metadata-only
probes. Public maintained Jetty 12 releases require a different API integration and are not a
compatible dependency-only replacement for this Site plugin.

Affected matches remain visible and **the Maven gate remains blocked**. There is no risk
acceptance, suppression or threshold change. Resolving this boundary requires an upstream Site
integration with maintained Jetty, a supported patched dependency source, or a separately reviewed
change to the supported producer tooling/boundary. A claim that CI is fully green would be incorrect.

The new local fixes are not yet a hosted matrix result. They must be committed/pushed by the
maintainer before the new workflow cohort can be checked. Real-suite performance measurement
remains deferred.

## Private reporting

GitHub Private Vulnerability Reporting was found disabled, enabled on 13 November 2026 and then
confirmed with the repository API returning `{"enabled":true}`. This verifies the setting, not a
report submission from another user's account. See [maintainer setup](security.md#maintainer-setup-for-private-reports).

References: [CycloneDX core validation advisory](https://github.com/advisories/GHSA-6fhj-vr9j-g45r),
[Bouncy Castle advisory](https://github.com/advisories/GHSA-9pwp-9qqc-pr26),
[Maven plugin dependency configuration](https://maven.apache.org/guides/mini/guide-configuring-plugins.html#using-the-dependencies-tag),
[Jetty request-smuggling advisory](https://github.com/advisories/GHSA-355h-qmc2-wpwf),
[Jetty authentication advisory](https://github.com/advisories/GHSA-2fvj-hgj9-j2gr),
[Jetty security reports](https://jetty.org/security.html),
[public Jetty version metadata](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-server/maven-metadata.xml).
