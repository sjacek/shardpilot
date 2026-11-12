# Preliminary review follow-up: 12 November 2026

[Security policy](../SECURITY.md) | [Trust boundaries](security.md) | [Testing](testing.md)

This is a maintainer-side follow-up to the preliminary OSINT/IT/cybersecurity report supplied
on 12 November 2026. It records reproducible repository evidence, documentation changes and
remaining work. It is not an independent penetration test, a dependency vulnerability scan or a
production-security approval. The report's recommendations are inputs to this review, not
instructions that authorize changes to GitHub settings, publication or consumer infrastructure.

## Reference snapshot

The user identified the reviewed version as the public GitHub state visible on 12 November 2026.
GitHub's read-only repository/commit/tag APIs established this reference:

| Item | Verified value |
| --- | --- |
| Repository / default branch | `sjacek/shardpilot` / `main`. |
| Public commit | [`58daef5436a01295ce46feea56aa307c41bbd0a0`](https://github.com/sjacek/shardpilot/commit/58daef5436a01295ce46feea56aa307c41bbd0a0), merged on 5 November 2026. |
| Stable tag | Annotated `v0.4.0`; tag object `cfc3fe141cdc21b484ed56d01a841f96427b1d25` resolves to the same public commit. |
| Committed source tree | `2ee53fd6b31f1c16b9ba2b0c4ff421ed05cee607`. |
| Local base | `24615bd62a7b9e960c86e5ed67281d3445fcb7c4`, on `release/0.4.0`; its committed tree is identical to the public reference tree despite the different commit identity. |
| Supplied PDF fingerprint | SHA-256 `de9fcf5594af99d13286ee5ed2dfb3dfddb2c0554f52188b690fc08ebbbc798d`. The privately supplied PDF is not copied into this repository. |

The PDF itself does not pin a commit; the reference above makes this follow-up reproducible.
It does not claim which individual files the original reviewer examined. Existing unrelated
staged/unstaged changes are outside this reference and were not reset or included in its tree identity.
The policy and follow-up documents are new working-tree changes, not controls present in the
original public snapshot.

Useful local identity checks, when the reference tag is available:

```shell
git rev-parse HEAD
git rev-parse 'HEAD^{tree}'
git show -s --format='%H %T %s' 'v0.4.0^{}'
```

## Classification of the report's findings

"Present" below means there is specific repository or historical CI evidence. It does not mean
that every attack scenario or remote setting has been verified.

| Finding / recommendation | Reference evidence | Follow-up / remaining work |
| --- | --- | --- |
| Formal private reporting policy is missing. | No `SECURITY.md` exists in the reference tree; the public security page reported no policy. | Policy and maintainer instructions are prepared in this change. The maintainer must separately verify/enable GitHub Private Vulnerability Reporting. |
| Distribution and release checks are visible. | The reference has wrapper checksums, a signed Maven release profile, release-tag validation and successful publication workflow records. | Already partly implemented. Re-download and verify the actual published artifact cohort separately; workflow success is not a fresh cryptographic verification of registry bytes. |
| Release state needs a precise documentation baseline. | Public `main` and `v0.4.0` resolve to the same commit, although the reference README/index still use release-preparation wording. | Clarified the current tag and checkout-evaluation wording in this change; historical release notes retain their preparation procedures. |
| Compatibility CI is positive. | The reference-commit run has 12 successful jobs: eight Gradle/JDK cells, three Maven/JDK cells and one upload-disabled signed Maven bundle check. | Reuse this evidence with its revision/date; it is historical project CI, not a new independent audit or a matrix rerun on 12 November. |
| Selection correctness needs an explicit boundary. | Existing manifests and core tests check class ownership, duplicates and cohort evidence. Documentation and HTML already distinguish class selection from test execution. | Preserve those gates; add reproducible baseline-versus-shards execution comparisons, including skips and invocations. Do not claim every method ran merely because manifests match. |
| Metadata/report processing needs scrutiny. | The XML importer disables DTD/external-entity processing; YAML parsing rejects duplicate keys/collection aliases; HTML escapes artifact-derived text. Existing tests cover specific failures. | Controls are present and the core suite was rerun below. This does not establish exhaustive malicious-input or denial-of-service resistance. |
| Build plugins can reach CI data/secrets. | Adapters run inside build-tool processes. Workflows separate compatibility from publishing secrets and define read-only repository permissions. | Documented in the trust-boundary guide. Actual runner isolation, environment protection, tag rules and account settings remain separate checks. |
| SBOM and dependency vulnerability analysis are needed. | No checked-in SBOM or dedicated SCA/CVE workflow was found in the reference tree. Gradle and Maven distributions have different dependency boundaries. | Open. Generate per-distribution resolved inventories and scan runtime plus producer dependencies with explicit scope and triage. No claim of zero CVEs is made. |
| CI supply-chain hardening needs evidence. | Actions use moving major-version references; wrapper distributions have fixed checksums. | Open. Review/pin Actions to immutable SHAs and define dependency-verification/update policy, retaining compatibility and publication regression gates. |
| Absence of telemetry needs independent confirmation. | Documentation declares no telemetry/coordinator/remote history fetching. Limited first-party source inspection is not a dynamic observation. | Open. Run distributed artifacts under a documented network policy after separately warming dependencies; retain network evidence. |
| Real performance improvement needs measurement. | Documentation explains duration balancing and startup/capacity limits. The supplied chart is illustrative, not a measured ShardPilot benchmark. | Open. Repeat equivalent full-suite and 2/4-shard runs on the same revision/resources; separate queue, warm-up, test and pipeline times and retain raw evidence. |
| Independent security assurance is absent from this review. | A public project, signatures, green CI and a selected AI assessment are different kinds of evidence. | Do not market this follow-up as an independent audit. Seek external review after reproducible evidence is ready. |

Star/fork counts are not used as a security acceptance criterion. Applicability follows the JVM
build/test ecosystem, not a desktop-versus-server distinction: a JVM desktop project can also
have a Gradle/Maven test suite suitable for sharding.

## Evidence inspected

- [Reference compatibility workflow](https://github.com/sjacek/shardpilot/blob/58daef5436a01295ce46feea56aa307c41bbd0a0/.github/workflows/compatibility.yml)
  and [successful reference run](https://github.com/sjacek/shardpilot/actions/runs/42054681889).
- [Reference publication workflow](https://github.com/sjacek/shardpilot/blob/58daef5436a01295ce46feea56aa307c41bbd0a0/.github/workflows/publish.yml),
  [Gradle publication run](https://github.com/sjacek/shardpilot/actions/runs/42058781494) and
  [Maven publication run](https://github.com/sjacek/shardpilot/actions/runs/42071602818), all for the reference commit.
- [Reference XML importer](https://github.com/sjacek/shardpilot/blob/58daef5436a01295ce46feea56aa307c41bbd0a0/shardpilot-core/src/main/java/io/github/sjacek/shardpilot/JUnitXmlTimings.java),
  [YAML parser](https://github.com/sjacek/shardpilot/blob/58daef5436a01295ce46feea56aa307c41bbd0a0/shardpilot-core/src/main/java/io/github/sjacek/shardpilot/ClassManifest.java) and
  [HTML renderer](https://github.com/sjacek/shardpilot/blob/58daef5436a01295ce46feea56aa307c41bbd0a0/shardpilot-core/src/main/java/io/github/sjacek/shardpilot/ShardCoverageHtml.java),
  with their corresponding core tests.
- Local wrapper configurations, Gradle/Maven dependency declarations, signed-bundle verification
  utility and release/selection documentation from the equivalent committed tree.

The repository setting for private vulnerability reporting could not be read with the available
GitHub connector. Its enabled state, protected-environment reviewers and tag rules were not
established. No repository settings were changed, no workflow was dispatched and no version
was published. Published JARs were not downloaded or dynamically monitored in this stage.

## Local verification for this follow-up

On Windows, using Eclipse Temurin 25.0.5 and the Gradle 9.8.2 Wrapper:

```shell
./gradlew :shardpilot-core:test --rerun-tasks --console=plain
```

Result on 12 November 2026: **165 core tests passed, zero failures/errors/skips** across ten
test classes. This includes 24 XML timing tests, 13 YAML manifest tests, nine HTML report tests
and class-selection/coverage/history cases. Tests exercise the equivalent core sources; no
runtime implementation was changed for this follow-up. This command does not run Gradle
TestKit, Maven Invoker, the complete compatibility matrix, an SCA scanner or a network audit.

The subsequent `./gradlew check assemble --console=plain` also completed successfully. Unchanged
compilation/test tasks, including the Gradle adapter suite, were `UP-TO-DATE`; publication metadata
tasks executed. This is not a fresh adapter functional-test run. A local documentation check
validated UTF-8, 126 relative file-link targets in the seven changed Markdown documents, reference
tree/tag identities and the policy's private-reporting URL. It did not verify external website
availability or enable repository settings.

## Next stages and acceptance criteria

1. **Operational private reporting:** review the policy, enable/verify GitHub's private reporting
   setting and confirm that a signed-in reporter can reach the form without submitting a real report.
2. **Supply-chain inventory and checks:** produce separate SBOMs for Gradle and Maven runtime
   distributions; distinguish provided/test/producer dependencies; record scanner/database versions
   and findings/triage. Review immutable Actions references and artifact provenance/checksums/signatures.
3. **Execution equivalence:** compare identities, multiplicity, statuses and skips from a deterministic
   native-runner baseline with the union of shards. Include JUnit, Spock and TestNG where the adapter
   actually supports them; document unsupported/dynamic identity cases instead of assuming equivalence.
4. **Isolated artifact execution:** document the sandbox and permitted paths/destinations; record
   outgoing attempts after dependency warm-up with no production secrets. Assess the actual published
   plugin artifacts and distinguish build-tool/test traffic from plugin behavior.
5. **Reproducible performance:** use a real JVM suite such as `tichodroma-svc`; repeat full-suite,
   two-shard and four-shard runs on equivalent resources, with separate cold/warm-cache cohorts.
   Compare total CI elapsed time, queue time and runner consumption, not just recorded planner weights.
6. **Publish bounded evidence:** retain commands, commit/artifact identities, environment and raw
   results with their limitations. Recheck the relevant matrix before any implementation/release change;
   an external review remains a separate activity.

At the initial follow-up, stages 2-6 remained planned work, not completed checks. The policy follow-up established
trust-boundary documentation and reference evidence without changing runtime code or CI behavior.

## Subsequent build-tool update

After the initial follow-up above, the maintainer requested a Gradle upgrade on the same date.
The working tree now separately updates the build Wrapper and newest consumer CI targets to
[Gradle 9.9.1](testing.md#gradle-991-wrapper-upgrade). Those changes and their verification are
not part of the original `58daef5` snapshot or the initial Gradle 9.8.2 checks recorded above.
Do not attribute the new Wrapper bytes or matrix to the already published reference artifacts.

## Subsequent runtime SBOM and SCA implementation

The [runtime dependency workflow](dependency-security.md) implements the runtime-distribution
part of stage 2: separate native Gradle/Maven inventories, candidate JAR/class/descriptor checks,
and pinned Grype scans with versioned DB evidence and a blocking severity policy. It does not
retroactively audit `58daef5` or registry artifacts, and does not complete producer/provided/test
inventories, the broader immutable-Actions/dependency-verification policy, or stages 3-6.

## Subsequent dependency integrity implementation

[Build dependency verification](dependency-verification.md) now pins repository-owned workflow
Actions to reviewed commit SHAs and adds producer/TestKit/Maven integrity baselines and CI guards.
This is a distinct control from runtime SCA: it binds resolved bytes, not advisory coverage or
publisher identity. Published-artifact verification, third-party action/toolchain download behavior,
execution equivalence, isolated execution and reproducible performance remain separate work.
