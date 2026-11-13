# Build dependency integrity

[Documentation index](README.md) | [Dependency SBOMs](dependency-security.md) | [Release gates](releases.md)

The producer build verifies reviewed SHA-256 identities independently of vulnerability scanning.
A clean CVE scan does not establish that downloaded bytes match the reviewed dependency, and a
matching checksum does not establish absence of vulnerabilities.

## Controls and scope

| Boundary | Control |
| --- | --- |
| Repository-owned GitHub workflows | Every remote `uses` points to a full commit SHA. `security/action-pins.json` records the upstream tag and peeled commit verified on 12 November 2026. Local reusable workflows use this checkout. |
| Gradle producer dependencies/plugins | Native strict dependency verification reads `gradle/verification-metadata.xml`, including POM/module metadata and build/test/runtime artifacts. No broad trusted-artifact exceptions are present. |
| Gradle TestKit consumers | `VerifiedTestKit` copies the same reviewed policy into isolated consumers before resolution; warmup also uses it on every supported Gradle version. |
| Maven producer dependencies/plugins | Maven Checksum Extension 0.0.5 is loaded at JVM startup, before dependency code. It enforces `.mvn/checksums.sha256` for resolved artifacts, including cached files and snapshots. |
| Maven verifier bootstrap | `scripts/maven_verification.py` downloads the exact Maven Central coordinate and verifies the SHA-256 in `security/dependency-verification.json` on every invocation, including an existing local copy. It never loads the extension through an unverified `extensions.xml` resolution. |
| Maven transport | `.mvn/maven.config` additionally enables `--strict-checksums`. Transport checksums alone would not protect an existing local repository or supply an independently reviewed baseline. |
| CI policy | `scripts/dependency_policy.py` rejects mutable/unreviewed Actions references, artifact-wide Gradle trust exceptions, missing SHA-256 values and checksum-generation/bypass commands in workflows. Collection mode is forbidden in CI. |

The Maven extension skips reactor artifacts because they are outputs of the source build, not
downloaded dependencies. Maven Invoker installs the current parent/core/plugin first; its pre-build
hook then binds exactly those three POMs and two JARs into a disposable consumer baseline. Other
coordinates still require the committed external checksums. Download-only fixture warmup uses the
same rule after installing the current reactor. Generated candidate checksums stay under `build/`;
they never enter shared download snapshots or the reviewed external baseline.

`gradle/verification-fixtures.gradle` adds a download-only configuration for JUnit, Spock and TestNG.
This lets Gradle's metadata writer cover the libraries resolved by isolated consumers. They are
not added to either plugin's distributed runtime dependencies.

Compatibility warmup, Maven matrix jobs, Maven SBOM jobs, signed-bundle verification and initial
Central publication prepare the verifier before invoking Maven. Neither normal CI nor publication
generates new trusted third-party checksums. The cache key includes both baselines and the verifier
policy. Restored caches remain untrusted until the native resolver checks their bytes.

## Local builds

Gradle uses the checked-in metadata automatically:

```shell
./gradlew check assemble
```

For Maven, use the verifier launcher so that the extension is installed and checked before JVM startup:

```shell
python3 -B scripts/maven_verification.py run -- -B -ntp verify
python3 -B scripts/maven_verification.py run -- -B -ntp -Psecurity-sbom -DskipTests -DskipITs package
```

On Windows use `gradlew.bat` and the installed Python executable. The launcher selects `mvnw.cmd`
and preserves argument boundaries. Calling `mvnw` directly only enables transport checksum checks;
it does not bootstrap the additional early verifier. CI uses `prepare` with `GITHUB_ENV` so that
existing Wrapper commands and the credential-isolated publisher inherit the verified JVM options.
Use a checkout path without whitespace on POSIX: Maven's native `MAVEN_OPTS` launcher does not
reparse embedded shell quotes. Windows startup options quote paths for the native batch launcher.

The launcher also builds and loads the producer-only [Maven tooling guard](dependency-security.md#explicit-maven-tooling-boundary).
Its API inputs use existing reviewed Maven checksums; its authored sources and scope policy are
retained as inventory evidence. Site execution and unclassified effective plugins are rejected
before goal execution, including native Invoker children that inherit the verified JVM options.
`python3 -B scripts/verify_maven_tooling.py` runs offline positive/negative controls and records
the exact guard JAR checksum. Diagnostic full-inventory findings never waive these execution controls.

Do not set the extension's relaxed/dry-run modes, disable Gradle verification, automatically accept
new sums from a failed build, or retain a corrupted verifier JAR. A mismatch needs investigation.

## Reviewed updates

1. Change the intended dependency/plugin version and inspect its upstream release and provenance.
   Use a clean or independently verified cache. Baseline generation is trust on first use, not
   proof of publisher identity or an independent security audit.
2. For Gradle, explicitly regenerate native metadata outside CI using an isolated resolver cache:

   ```shell
   GRADLE_USER_HOME="$PWD/build/verification-refresh/gradle" ./gradlew --no-configuration-cache --refresh-dependencies --write-verification-metadata sha256 help
   ```

   The root configuration includes producer and fixture libraries. Review every added coordinate,
   artifact and checksum. A previously populated cache can hide parent POMs, imported BOMs and
   module metadata needed while resolving versions that are later evicted from the final graph.
   Verify the completed baseline in enforcement mode using another fresh cache, including the
   supported TestKit warmup versions. Do not add a broad trust rule to silence a missing entry.
   Before completing a dependency, plugin or Wrapper update, also prepare and verify the IDE inputs:

   ```shell
   python3 -B scripts/ide_dependency_verification.py update
   python3 -B scripts/ide_dependency_verification.py check --verify-downloads
   ```

   The updater independently downloads missing sources from Maven Central or the Gradle Plugin
   Portal, validates a published checksum sidecar and records SHA-256 in the native metadata.
   It preserves existing checksums and never derives trust from the developer's resolver cache.
   Review the XML diff and `build/ide-dependency-verification/review.json` together with the version
   change. A sidecar confirms repository consistency; it does not prove publisher identity.
   The command requires the binary/plugin baseline to have been reviewed first, because the
   Wrapper still configures the build with native strict verification enabled.

   Every binary coordinate in the baseline, including classifier-only dependencies, must have
   a source checksum. The gate also discovers Groovy modules from the running Wrapper and requires
   their source and module checksums, so a Wrapper update cannot leave the IDE using stale SDK pins.
   `gradle/ide-source-availability.json` permits only exact source coordinates confirmed absent
   with HTTP 404 in both official repositories, bound to the reviewed binary SHA-256 values.
   Authentication, rate-limit and server errors fail the command. The online check rejects an
   absence record if sources have since appeared; it never creates a native trust exception.

   Compatibility CI runs the online check on every preparation, including exact cache hits,
   before matrix jobs can run. It checks downloaded bytes against reviewed SHA-256 values and
   cannot update either policy. Run `check` without `--verify-downloads` for the local coverage
   check (the Wrapper still needs its ordinary build dependencies). Regression controls cover
   dependency/SDK version changes, altered bytes, stale absence records and CI write attempts.

3. For Maven, explicitly collect checksums outside CI using the same lifecycle/profiles as the
   intended build, then review the diff. This mode may execute the dependencies being collected:

   ```shell
   python3 -B scripts/maven_verification.py run --collect -- -B -ntp verify
   python3 -B scripts/maven_verification.py run --collect -- -B -ntp -Psecurity-sbom,central-release -Dgpg.skip=true -DskipTests -DskipITs install
   python3 -B scripts/maven_verification.py run --collect -- -B -ntp -Psecurity-sbom,central-release org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline
   python3 -B scripts/maven_verification.py warm --collect
   ```

   The last command includes the download-only fixture graphs and engine providers used by CI warmup.
   Collection preserves previous entries to support multiple profiles; remove obsolete entries
   only after reviewing their use across the supported matrix. Candidate coordinates must not
   appear in the committed baseline.
4. Verify any new Maven verifier JAR against the intended official distribution before updating
   its pinned policy digest. A version edit alone must not authorize new executable bytes.
5. Run the checks below and the applicable Gradle/Maven builds in enforcement mode. A successful
   collection run is not an enforcement test. Recheck the hosted matrix after the maintainer pushes.

Dependabot proposes weekly Actions updates. Review the upstream repository/tag, resolve annotated
tags to their commit, update `security/action-pins.json` together with the workflow SHA, and retain
the human-readable version comment. An automated PR does not approve a new pin or baseline.

## Verification and limits

```shell
python3 -B scripts/dependency_policy.py
python3 -B -m unittest discover -s scripts -p 'test_dependency*.py' -v
./gradlew :shardpilot-gradle-plugin:test --tests io.github.sjacek.shardpilot.DependencyVerificationFunctionalTest
```

The Gradle functional test serves harmless fixture bytes over loopback, then stops the server and
checks that changed cached bytes and missing checksums fail offline. Python regressions cover a
corrupted verifier, a bad download, missing/duplicate baselines, CI collection attempts, conflicting
JVM options and the narrow local-candidate rule. Workflow regressions check bootstrap ordering.

`python3 -B scripts/verify_dependency_rejection.py` additionally proves native Maven rejection:
an offline synthetic parent resolves with its reviewed checksum, then changed cached POM bytes
and a missing checksum each fail before any project plugin runs. CI runs this positive/negative
control after warmup; logs and a JSON result stay under `build/dependency-verification/` and are
retained in the `dependency-verification` CI artifact for 14 days.
The same artifact retains Gradle dependency-verification HTML reports after warmup failures,
so missing checksums and checksum mismatches can be diagnosed from the downloaded evidence.

## Local validation on 12 November 2026

The initial enforcement baseline covered 309 Gradle artifact records and 902 external Maven identities.
Independent fresh downloads of the 1,136 distinct artifact paths represented across both baselines
matched the reviewed SHA-256 values from Maven Central or the Gradle Plugin Portal.

| Check | Result |
| --- | --- |
| Gradle `check assemble`, consumer Gradle 9.9.1 / Temurin 21.0.13 | 332 tests, no failures/errors/skips; native verification enabled. |
| Maven `verify`, Temurin 21.0.13 | 302 unit tests and all 15 Invoker fixtures plus two examples passed in enforcement mode. Core cases are shared with the Gradle run. |
| Download warmup | Gradle/TestKit 9.0.0, 9.1.0 and 9.9.1 plus the Maven reactor/fixture graphs passed with verification enabled. |
| Python CI regressions | 72 cases, no failures; one existing real-symlink case skipped on Windows. Concurrent consumer hooks and Windows replacement denial are covered. |
| Native Maven cache probe | Positive control passed; changed cached POM and missing checksum were both rejected. Gradle's functional probe likewise rejected changed cached JAR bytes and a missing checksum offline. |
| Signed release check | Nine artifacts and nine valid signatures; upload disabled and disposable test identity only. |
| Workflow checks | All three workflows passed Actionlint; the policy gate accepted all 40 remote Actions references at their reviewed SHAs. |

Local evidence stays under ignored `build/`: `dependency-verification/local-test-results.json`,
`dependency-verification/native-rejection.json`, `dependency-origin-audit/summary.json`,
`release-verification/report.json` and the `dependency-*-enforce.log` / Python test logs.
These initial local runs used existing resolver caches. The first hosted run and its cold-cache
follow-up are recorded below; local success did not establish a green hosted Linux/JDK matrix.

## Cold-cache CI follow-up

[Run 43242198830](https://github.com/sjacek/shardpilot/actions/runs/43242198830), for commit
`1a5d421b606da051ee3b35706dfc0a025f326a31`, passed the immutable-Actions policy and the signed Maven
release check, including nine signatures with upload disabled. Gradle warmup failed because nine
parent/BOM POM or module descriptors had no checksum entries. Required matrix jobs then failed their
prerequisite gate; runtime SBOM scans did not run. This was not evidence of failing plugin tests
or new vulnerability findings.

A separate empty Gradle/TestKit cache reproduced the same nine missing entries locally. They were
retrieved independently from Maven Central, checked against their declared artifact/version and
published SHA-1 sidecars, and hashed with SHA-256. Native metadata regeneration added exactly those
nine entries, preserving every existing checksum and verification setting. The completed baseline
has 318 Gradle artifact records; the combined Gradle/Maven origin audit matched all 1,144 distinct
artifact paths. This checks the retrieved bytes, not publisher identity.

Enforcement with a second empty Gradle/TestKit resolver cache passed warmup for Gradle 9.0.0,
9.1.0 and 9.9.1 plus all 16 native cache-rejection/workflow tests on Temurin 21.0.13, in 3m 39s.
All 15 Python policy/bootstrap regressions and Actionlint for the three workflows also passed.
Local evidence is `build/dependency-cold-enforcement.log`,
`build/dependency-cold-verification/validation.json` and the independent metadata review under
`build/dependency-cold-verification/reviewed/`.

The warmup evidence artifact now also retains native Gradle verification reports after failure.
The corrected revision must still pass the hosted matrix after the maintainer commits and pushes it.

## Remaining boundaries

SHA-256 verification identifies reviewed bytes, not an author's identity or exploitability. Its
integrity depends on review and repository protection. These controls do not verify already
published registry artifacts, all transitive executable downloads performed by third-party Actions,
JDK distributions or arbitrary external processes. Gradle's metadata does not cover distributions
downloaded outside its dependency resolver. Existing Wrapper distribution checksums remain separate.
Dependency locking/version-selection policy and the broader artifact/network audit remain separate
from this integrity gate.

Primary references: [Gradle dependency verification](https://docs.gradle.org/current/userguide/securing_dependencies.html),
[Maven transport and trusted checksums](https://maven.apache.org/resolver/expected-checksums.html),
[Maven Checksum Extension](https://github.com/raphw/maven-checksum-extension),
[Invoker hooks](https://maven.apache.org/plugins/maven-invoker-plugin/examples/pre-post-build-script.html).
