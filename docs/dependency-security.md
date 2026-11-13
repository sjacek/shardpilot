# SBOMs and dependency vulnerability scanning

[Documentation index](README.md) | [Trust boundaries](security.md) | [Release gates](releases.md)

ShardPilot keeps **Gradle and Maven** inventories separate. Each distribution has four
CycloneDX 1.6 reports: **runtime, build, test and provided**. They describe resolved dependencies
of the checked-out candidate and its verification tooling. They do not claim to scan already
published registry bytes, the entire runner, or every supported consumer installation.

## Scope boundaries and provenance

| Scope | Gradle | Maven |
| --- | --- | --- |
| Runtime | Native CycloneDX Gradle plugin 3.5.1, `runtimeClasspath` in both modules; embedded core class bytes are checked against the candidate JAR. | Native CycloneDX Maven plugin 2.9.3, compile/runtime scopes; coordinates must match the candidate plugin descriptor. |
| Build | Root/module buildscript `classpath`, including Plugin Publish, CycloneDX and their resolved transitives. | Dependency plugin 3.11.0 `resolve-plugins` for all reactor projects with `security-sbom,central-release`: effective build/report plugins, their added dependencies and transitives. Includes the independently pinned startup checksum verifier. |
| Test | Module test compile/runtime classpaths, the Jupiter/Spock/TestNG execution fixture classpaths and root `verificationFixtures`, including the declared lazy Groovy compiler inputs. Gradle API/TestKit file dependencies are retained too. | Complete reactor test classpaths, native dependency trees of the actual Invoker/example POMs, and an explicit model of the dynamically selected Surefire JUnit provider using the reactor's managed JUnit versions. |
| Provided | A resolvable view of `compileOnly` plus SDK file dependencies from the actual compile classpath. | Transitive closure of effective direct `provided` roots, selected using native `dependency:tree` JSON and the fully resolved CycloneDX graph. |

Maven's test classpath includes compile/runtime/provided libraries. Filtering out compile scope
while generating a provided-only graph loses transitives of host APIs, so finalization resolves
those edges first, then selects the provided roots and their closure. Scope reports can overlap:
**do not add their counts as unique packages or vulnerabilities**. Every merged component keeps
its source project, configuration/profile/selection and original native scope. Native excluded/test
markers remain in provenance, while the dedicated scan document marks these components required
for that scan, so the scanner does not silently skip them as non-runtime packages.

Third-party SDK JARs are matched by exact SHA-256 against reviewed Maven Central artifact identities
in `security/gradle-sdk-artifacts.json`. Thirteen SDK libraries now have their actual Maven PURL and
library version, with the supplying Gradle version recorded separately. This registry performs no
remote lookup in CI. Changed bytes or an unreviewed third-party SDK file fail preparation.

Generated Gradle API/TestKit JARs and the internal installation beacon retain real file hashes and
`pkg:generic/gradle/` identities. Their version identifies the providing Gradle distribution. This
**does not decompose their bundled classes or guarantee advisory matching for embedded code**.
The buildscript resolver's synthetic
`unspecified/unspecified` configuration root is mapped to the owning project; it is not reported
as a fictitious external library. Raw native graphs are retained unchanged.

The Gradle runtime check compares every compiled core class with its bytes in the assembled JAR
and requires every core runtime dependency in the adapter graph. Maven runtime coordinates must
match `META-INF/maven/plugin.xml`; candidate SHA-256 values must agree with generator hashes.
Runtime validation still rejects test/excluded components, duplicate or dangling references and
unreachable components. The broader inventories do not weaken that shipped-runtime boundary.

`scripts/security_reports.py` retains these runtime and scanner checks. `scripts/security_scopes.py`
merges the additional native inventories, binds them to the same candidate hashes and commit,
checks retained native evidence, scans every scope, and produces the distribution-wide gate.
These are additional boundary checks, not a general CycloneDX schema validator. Maven's
resolve-plugins output is a flat plugin-realm membership inventory: its edges do not pretend to
recover the exact transitive parent inside each realm. Fixture graphs retain native tree edges.

The Maven build evidence also retains `tooling-model.json`, referenced through the build
inventory's `inputs`/`retainedFile` records. It distinguishes effective build plugins from
managed-only declarations and records execution IDs, phases and goals from the effective POM.
Site-only bindings are labelled `site-lifecycle-only`. These are model bindings, not proof of
execution; a directly invoked plugin can be used without any bound execution. All resolved
realms remain in the same SBOM and severity gate regardless of the label.

Only explicit plugin identity and binding fields are retained. The complete effective POM can
contain interpolated local settings, so it is generated in a temporary directory and removed
after extraction, including on failure. Arbitrary properties and plugin configuration are never
copied into the uploaded explanatory report.

## CI and release gating

`.github/workflows/security.yml` runs separate `gradle` and `maven` jobs after dependency warmup.
The Gradle/Maven test matrices also start after that warmup, in parallel with the scans. Their
statuses describe native tests; both security job contexts are separately required for merging.
The reusable workflow still fails when any matrix, signing check or security job fails, blocking
both publication destinations. No `continue-on-error` or severity-policy exception is introduced.
Gradle warmup also resolves `securityInventories`; Maven warmup resolves the release plugin model.
Only downloaded dependencies are shared with the scan jobs. The cache key includes the Gradle/POM
models, checksum baselines and inventory helper. Maven scan preparation installs the current
candidate only in the isolated local repository for verified fixture metadata resolution; candidate
artifacts remain excluded from exported download caches.

Activating the Maven release model is **resolution only** through a direct dependency-plugin goal,
with `central.skipUpload=true`. No release lifecycle, signing or upload goal runs. The actual
Invoker/example models are copied as POMs only and resolved with `dependency:tree`: no test code,
consumer lifecycle or verification hook runs. All external resolutions retain checksum enforcement.
The opt-in `security-sbom` profile uses `skipAttach`; reports are not publication attachments.

The jobs use read-only repository permissions and no publisher secrets. They run manually, from
the reusable compatibility workflow, and daily against the default branch. Both compatibility
matrices require successful warmup and successful security checks. A failure explicitly fails the
existing required contexts; publication depends on the whole successful reusable workflow.
A scheduled finding does not revoke a previously published release.

The scan loop continues through all four scopes after a severity failure or process error. It
records every native exit code, then independently validates every report. One failed, missing,
invalid or inconsistent scope fails the entire distribution. A clean runtime report alone is
never sufficient. Artifacts upload with `always()`; earlier generator failures can retain only
partial evidence. No `continue-on-error` converts a security failure into success.

## Policy and report layout

`security/scan-policy.json` pins **Grype 0.121.3**, **high** as the blocking threshold, inclusion
of unfixed findings, and a maximum DB age of **120 hours** for every scope. The pinned downloader
uses tag `v0.121.3`, while native scanner output must report plain version `0.121.3`. CI explicitly
refreshes the DB and records its source/build/schema. The scanner runs locally, without submitting
project inventories to an analysis API. Package exclusions, VEX filtering and suppressed matches
are rejected by the reporting policy.

High and critical matches block even without a fix. Medium, low, negligible and unknown results
remain available for triage. Stale/different DBs, unexpected scanner versions, changed SBOMs or
native evidence, another scanned file, contradictory exit statuses and different candidate/commit
cohorts fail closed. Preparation invalidates previous findings and summaries before building a
new cohort; failed summarization removes old green summaries.

For each distribution, `build/security/<distribution>/` contains:

| Location | Evidence |
| --- | --- |
| `bom.cdx.json`, `inventory.json`, `findings.json`, `scan-status.json`, `summary.json`, `summary.md` | Runtime inventory, candidate binding, native scanner JSON/exit code and validated result. |
| `build/`, `test/`, `provided/` | The same SBOM/inventory/findings/status/summary files for each additional scope. |
| `<scope>/native/` | Unmodified native BOMs, resolution reports, scope evidence, POMs and the SDK identity registry, with hashes in that scope's inventory. |
| `scanner.json`, `database.json` | Actual scanner and fresh DB identities, also copied into the individual scope reports. |
| `scopes-summary.json`, `scopes-summary.md` | Result for **all four scopes**, with a separate status for each; also added to the job UI. |

Artifacts **`dependency-security-gradle`** and **`dependency-security-maven`** retain the reports
for 30 days, including after severity failures. Full vulnerability DBs, downloaded JARs, signing
keys and publication credentials are not uploaded.

Exceptions need an advisory, affected package/distribution/scope, impact assessment, owner,
rationale and expiry, with reviewed policy/reporting tests. A producer-tool finding is not proof
of an exploitable vulnerability in the shipped plugin. It still requires triage under this gate.

## Local generation and verification

Use JDK 21, Python 3.12+ and the pinned Grype binary from its official release, verified against
its published checksum. From the repository root:

```shell
./gradlew securityInventories --no-configuration-cache
python3 -B -c "import os,sys; sys.path.insert(0,'scripts'); import maven_verification as v; os.environ.update(v.environment()); import maven_security_inventory as s; s.main()"
python3 -B scripts/security_scopes.py prepare --distribution gradle
python3 -B scripts/security_scopes.py prepare --distribution maven
```

The inline local command bootstraps the verifier before calling the Maven helper; it enforces
committed hashes rather than collecting new ones. GitHub Actions instead runs
`scripts/maven_verification.py prepare` to write settings to `GITHUB_ENV`, then invokes
`scripts/maven_security_inventory.py` in that verified environment.

Preparation records the immutable Git SHA and working-tree dirtiness; a modified checkout is
not asserted to be a clean copy of that commit. CI supplies the SHA explicitly. On PowerShell,
use `./gradlew.bat` and your Python executable. The inventory tasks opt out of Configuration
Cache because their scope evidence reads the producer model; this does not alter the published
plugin's Configuration Cache support.

For each distribution, with `DISTRIBUTION=gradle` or `maven`, record scanner/DB identities **after**
preparation, then run both commands even if scanning returns nonzero:

```shell
export GRYPE_CHECK_FOR_APP_UPDATE=false GRYPE_DB_AUTO_UPDATE=false GRYPE_DB_VALIDATE_AGE=true
export GRYPE_DB_MAX_ALLOWED_BUILT_AGE=120h
grype db update --config security/grype.yaml
grype version -o json > "build/security/$DISTRIBUTION/scanner.json"
grype db status -o json --config security/grype.yaml > "build/security/$DISTRIBUTION/database.json"
scan_status=0
python3 -B scripts/security_scopes.py scan --distribution "$DISTRIBUTION" --grype grype || scan_status=$?
python3 -B scripts/security_scopes.py summarize --distribution "$DISTRIBUTION"
# A nonzero scan_status still means scan failure, even if a caller ignores the shell status.
```

A scan error is never a clean result. A summary is created for severity failures and an aggregate
failure record is retained even when a scope's evidence cannot be validated. Regression commands:

```shell
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
./gradlew :shardpilot-gradle-plugin:test --tests io.github.sjacek.shardpilot.PublicationWorkflowTest
```

## Initial findings before remediation: 13 November 2026

Initial local Grype 0.121.3 scans used DB `v6.1.10`, built **2026-11-13T06:20:21Z**. Both runtime inventories
had zero matches. Extended scopes exposed findings that were outside the former runtime boundary:

| Distribution / scope | Critical | High | Medium | Result |
| --- | ---: | ---: | ---: | --- |
| Gradle runtime | 0 | 0 | 0 | Pass |
| Gradle build | 0 | 4 | 0 | Block |
| Gradle test | 0 | 4 | 0 | Block |
| Gradle provided | 0 | 0 | 0 | Pass, with the SDK identification limitation above |
| Maven runtime | 0 | 0 | 0 | Pass |
| Maven build | 2 | 31 | 24 | Block |
| Maven test | 0 | 1 | 0 | Block |
| Maven provided | 0 | 1 | 0 | Block |

These are scanner matches per scope, not deduplicated advisory totals or reachability findings.
Examples include Jackson core/databind 2.22.2 in Gradle tooling, Bouncy Castle `bcprov-jdk18on` 1.81
in Maven plugin realms, and Guava 33.6.0-jre in provided Maven API transitives. Native findings
retain advisory identifiers, selected versions and reported fixes. No exclusion or severity-policy
change was introduced to turn these results green. Remediating/triaging producer dependencies is
a separate follow-up; **the expanded gate is expected to block the present dependency set**.

This is local implementation evidence, not a claim that the new workflow has run in GitHub.
The next push must verify Linux generation, the new reports and both matrix gates.

Local implementation checks passed:

- **85 Python tests** in a network-none, read-only Linux container using Python 3.12.16, including
  native-input provenance, transitive provided selection, fixture/provider inclusion, duplicate
  package merging, per-scope failures and aggregate gate rejection.
- **17 PublicationWorkflowTest cases**, native Gradle inventory generation, native Maven reactor
  generation, release-plugin resolution and **24 fixture/provider dependency trees**.
- Actionlint for all four workflow files, immutable-input policy checks and `git diff --check`.
- **45 additional Gradle metadata checksums** were compared with official Maven Central sidecars
  or artifact bytes; all 318 earlier Gradle entries remained unchanged. Maven's 902-entry baseline
  and all 43 pinned action references remain enforced.

The inventories contained Gradle runtime/build/test/provided counts **2/63/93/15** and Maven
counts **5/266/49/33** (excluding the root metadata component). One repeated Windows/Python 3.14
full-suite run hit an existing intermittent concurrent checksum-file replacement denial; the
complete suite passed on the Linux/Python 3.12 environment used for CI script validation.

## Limits

See the [13 November remediation follow-up](dependency-remediation-2026-11-13.md) for scoped
producer dependency updates, exact SDK identities, current scans and the unresolved Jetty boundary.

A zero-match result means no matching advisory in the recorded inventory/DB, not absence of
vulnerabilities, exploitability/reachability analysis or an independent audit. Unreported shaded
or vendored code can be missed. Generated/internal SDK files retain the advisory-matching limitation
above even after identifying the thirteen third-party JARs. The provided report inventories the build's actual API baseline; it is not a separate SBOM
of every Gradle/JDK/Maven combination in the compatibility matrix. Fixture project classpaths and
the selected Surefire provider are covered; arbitrary consumer build environments are not.
CI operating-system packages, JDKs, scanner internals, arbitrary action internals and every nested
consumer plugin ecosystem require other inventories. DB identity is retained, not a DB copy;
exact replay depends on upstream snapshot availability. Published-artifact integrity/network
observation and real-suite performance measurements remain separate checks.

References: [Gradle generator 3.5.1](https://github.com/CycloneDX/cyclonedx-gradle-plugin/tree/cyclonedx-gradle-plugin-3.5.1),
[Maven generator 2.9.3](https://github.com/CycloneDX/cyclonedx-maven-plugin/tree/cyclonedx-maven-plugin-2.9.3),
[Maven plugin resolution](https://maven.apache.org/plugins/maven-dependency-plugin/resolve-plugins-mojo.html),
[Grype 0.121.3](https://github.com/anchore/grype/releases/tag/v0.121.3).
