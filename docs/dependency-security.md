# Runtime SBOMs and vulnerability scanning

[Documentation index](README.md) | [Trust boundaries](security.md) | [Release gates](releases.md)

ShardPilot produces independent CycloneDX 1.6 inventories from native dependency resolution.
They describe candidate runtime distributions built from the checked-out commit, not the
producer's entire machine, CI tooling or already published registry bytes.

| Distribution | Generator | Core delivery | Excluded from runtime components |
| --- | --- | --- | --- |
| Gradle | CycloneDX Gradle plugin 3.5.1, `runtimeClasspath` | Core classes embedded in the plugin JAR. | Gradle-provided APIs, tests, buildscript plugins and scanner dependencies. |
| Maven | CycloneDX Maven plugin 2.9.3, `makeBom` | Separate `shardpilot-core` runtime dependency. | Provided/system/test scopes, build/report plugins and scanner dependencies. |

The Gradle finalizer joins core/plugin runtime graphs, requires all resolved core dependencies
in the plugin runtime, and compares **every compiled core class** with the bytes in the assembled
JAR. Its embedded component records class count and a named class-tree SHA-256 property, not a
checksum pretending to identify a separately distributed core JAR. The Maven inventory must match
all dependency coordinates in `META-INF/maven/plugin.xml`. Recorded candidate JAR hashes must
match native generator hashes when present. Both inventories reject duplicate/missing graph
references, unreachable components and test/excluded components.

`scripts/security_reports.py` adds these distribution checks, source identities and artifact
SHA-256 values. Its checks supplement native generation/validation; they are not a general
CycloneDX schema validator. Grype reads the finalized inventory.

## CI and publication gates

The compatibility workflow calls `.github/workflows/security.yml` after dependency warmup.
Independent `gradle` and `maven` jobs restore only their own download snapshots. Both test matrices
require successful warmup **and** security scans. Failed prerequisites explicitly fail the existing
required contexts rather than skip them; the 12 existing ruleset names remain valid. Publication
requires the entire reusable compatibility workflow, so either scan failure prevents either
credentialed publisher from running. Security jobs use `contents: read` without publication secrets.

Scans also run daily on the default branch and support manual dispatch, catching new advisories
without source changes. A scheduled failure does not revoke an existing release. Reports remain
CI artifacts; Maven's opt-in `security-sbom` profile uses `skipAttach` and adds no publication classifiers.

## Policy and reports

[`security/scan-policy.json`](../security/scan-policy.json) pins Grype **0.121.3**, a **high** blocking
threshold, inclusion of unfixed vulnerabilities and a maximum DB age of **120 hours**. The download
action uses the immutable Anchore Scan Action 7.4.2 commit and the release tag `v0.121.3`, so its
installer is pinned too. The policy keeps the plain version `0.121.3` used in native scanner reports;
the policy command emits a separate `scanner-release-tag` for the download action.
The job refreshes the DB explicitly,
records binary version and DB source/build/schema, and scans locally; it submits no project
inventory to a scanning API. The explicit Grype configuration avoids auto-discovering another
project's configuration. Package exclusions, VEX filtering and suppressed runtime matches are
not accepted by the current reporting policy.

High/critical findings block even without an available fix. Low, medium and unknown-severity
findings remain visible for triage. Scanner/DB failures have no `continue-on-error`. Missing or
invalid evidence, stale DBs, wrong versions, another scanned file or a changed SBOM cannot become
a green summary. A failed rerun removes previous summaries. Exceptions require an advisory,
affected package/distribution, impact assessment, owner, rationale and expiry plus reviewed
policy/reporting tests; do not lower thresholds or enable `only-fixed` to make a release green.

Each `build/security/<distribution>/` contains:

| File | Evidence |
| --- | --- |
| `bom.cdx.json` | Runtime components/graph, source identity and candidate artifact hashes. |
| `inventory.json` | Candidate JAR hashes, core delivery, generator metadata and excluded scopes. |
| `scanner.json`, `database.json` | Actual binary and DB identities, source/checksum URL and validity. |
| `findings.json` | Native Grype output including configuration, DB/provider identities and findings. |
| `scan-status.json` | Native exit code, checked against findings; scanner errors cannot become a clean scan. |
| `summary.json`, `summary.md` | Validated policy result, also added to the job UI. |

Artifacts `runtime-security-gradle` and `runtime-security-maven` are retained 30 days, including
after severity failures. Earlier failures may have only logs/partial evidence. Full DB files and
publication/signing credentials are not uploaded.

## Local generation and scanning

Use JDK 21, Python 3.12+ and the pinned Grype binary from its official release after verifying the
published checksum. Run from the repository root; these commands do not publish:

```shell
./gradlew :shardpilot-gradle-plugin:jar :shardpilot-core:cyclonedxDirectBom :shardpilot-gradle-plugin:cyclonedxDirectBom
./mvnw -B -ntp -Psecurity-sbom -DskipTests -DskipITs package
python3 -B scripts/security_reports.py prepare --distribution gradle
python3 -B scripts/security_reports.py prepare --distribution maven
```

Preparation records Git SHA and working-tree dirtiness; local changes are not asserted to be a
clean copy of that commit. CI supplies its immutable checkout SHA explicitly. On PowerShell use
`./gradlew.bat`, `./mvnw.cmd` and your Python executable. For each distribution, replacing `gradle`
with `maven` when needed:

```shell
export GRYPE_CHECK_FOR_APP_UPDATE=false
export GRYPE_DB_AUTO_UPDATE=false
export GRYPE_DB_VALIDATE_AGE=true
export GRYPE_DB_MAX_ALLOWED_BUILT_AGE=120h
grype db update --config security/grype.yaml
grype version -o json > build/security/gradle/scanner.json
grype db status -o json --config security/grype.yaml > build/security/gradle/database.json
if grype sbom:build/security/gradle/bom.cdx.json --config security/grype.yaml \
  --output json --file build/security/gradle/findings.json --fail-on high --only-fixed=false --show-suppressed; then
  scan_exit=0
else
  scan_exit=$?
fi
printf '{"exitCode":%s}\n' "$scan_exit" > build/security/gradle/scan-status.json
python3 -B scripts/security_reports.py summarize --distribution gradle
```

A severity failure is nonzero but retains findings; run the summarizer afterwards. A scanner
error is not a clean scan. Preparation clears earlier scan metadata, so record it again each time.
Regression tests use synthetic inventories, real temporary JARs and injected scanner JSON:

```shell
python3 -B -m unittest discover -s scripts -p 'test_security_reports.py' -v
./gradlew :shardpilot-gradle-plugin:test --tests io.github.sjacek.shardpilot.PublicationWorkflowTest
```

## Local implementation verification

On 12 November 2026, Windows/Temurin 21.0.13 verification passed:

- Fresh Gradle `check assemble` with consumer Gradle 9.9.1: **330 tests**, no failures/errors/skips.
- Maven `verify`: **302 unit tests**, **15 Invoker fixtures** and **two runnable examples**, all passed.
- Python script suite: **55 cases**, one real-symlink export case skipped on Windows; the 12
  security-report tests passed, including scanner errors and exit-code/result disagreement.
- Final workflow regression suite: **14 tests** passed after the exit-status evidence change.
- Actionlint 1.7.12 accepted all three workflow files (YAML/expression checks; external ShellCheck
  and Pyflakes integration disabled).
- Git Bash `-n` accepted all nine `run` scripts extracted from the security workflow.
- Native Grype 0.121.3 scans reported **zero findings in both runtime inventories**, using DB
  `v6.1.10`, built `2026-11-12T06:20:58Z`. Reports bind the final candidate JARs and record a dirty
  local working tree, not a claimed published release cohort.
- A separate metadata-only positive control for `org.apache.logging.log4j:log4j-core:2.14.1`
  detected Log4Shell, returned eight matches (three high/critical) and native exit code **2**.
  No vulnerable JAR was downloaded or executed for that control.

The first hosted Linux run generated both inventories and verified their candidate artifacts, but
stopped before scanning: the download action received `0.121.3` instead of the required release
tag `v0.121.3`. The corrected mapping has a CLI-output regression test. Hosted scans, scheduled
execution and successful matrix execution behind the new gate still need a successful workflow run;
local verification does not claim those remote executions occurred.

## Limits and remaining work

Zero findings mean no matching advisories reported for this inventory by the recorded scanner/DB
at that time, not absence of vulnerabilities, reachability/exploitability analysis, independent
review, reproducible builds or signed provenance. Dependency graphs can miss unreported shaded
or vendored code. Published artifact cohort verification and complementary binary analysis remain
separate work, as do provided/test/producer inventories and the broader immutable-Actions and
dependency-verification policies in the [review follow-up](security-review-2026-11-12.md).
DB identity is retained, not a DB copy; exact replay depends on upstream snapshot availability.

References: [Gradle generator](https://github.com/CycloneDX/cyclonedx-gradle-plugin/tree/cyclonedx-gradle-plugin-3.5.1),
[Maven generator](https://github.com/CycloneDX/cyclonedx-maven-plugin/tree/cyclonedx-maven-plugin-2.9.3),
[Grype release](https://github.com/anchore/grype/releases/tag/v0.121.3),
[pinned download action](https://github.com/anchore/scan-action/tree/27805bf3b4e84b4a5c980df22ed233c00390a439).
