# Published artifact and isolated execution audit

[Security boundaries](security.md) | [Review follow-up](security-review-2026-11-12.md) |
[Dependency integrity](dependency-verification.md)

This maintainer check defaults to public **0.5.0** distribution bytes, separately from the development
checkout and locally built release candidates. It is bounded execution evidence, not an independent
security certification, a reproducible-binary-build proof or a guarantee about every consumer.

## Registry and source checks

`scripts/published_artifacts.py` downloads the nine Maven Central release files (parent POM plus
core/adapter POMs and binary/source/Javadoc JARs), the Gradle marker POM, implementation POM/module
metadata and its three JARs. Downloads retain original/final HTTPS URLs, sizes and local SHA-256s.

- Maven Central SHA-256/SHA-512 sidecars are compared with the downloaded bytes. Its nine detached
  OpenPGP signatures are checked separately using a fresh public-only GnuPG home.
- The Gradle marker must point to `io.github.sjacek:shardpilot:0.5.0`. Module metadata's artifact
  SHA-256s must match the actual binary/source/Javadoc JARs. Absent Portal sidecars/signatures are
  recorded as absent, rather than presented as independently verified signatures.
- Java sources in each source JAR must match the appropriate modules of `v0.5.0`; only CRLF/LF
  differences are normalized. Missing/extra/changed Java sources fail verification. ZIP entries
  are checked for duplicates, traversal, absolute paths, symlinks and excessive expansion.
- Published class-file versions must not exceed Java 17. Source comparisons do not prove that
  those exact sources produced every compiled byte; no reproducible build claim is made.
- The public tag object must resolve to the same source commit. Its detached payload/signature
  is checked locally along with the artifact signatures. Public keys come from
  [the publisher's GitHub public-key endpoint](https://github.com/sjacek.gpg). Record the actual
  signing/primary fingerprints; this is not independent, out-of-band identity authentication.
  The tag/artifact signer must also match the reviewed fingerprint in
  [`security/published-artifact-audit.json`](../security/published-artifact-audit.json).

Registry sidecars and module metadata share a distribution trust boundary with their artifacts.
They detect mismatches but do not, by themselves, establish publisher identity or build provenance.

## Sandbox and measurement

The launcher builds an audit-tools image from a digest-pinned public Temurin JDK. Ubuntu packages
provide Python, GnuPG and `strace`; the resulting image ID is recorded. Package installation may
change on a later rebuild, so the Dockerfile alone is not a fully immutable toolchain definition.
Gradle 9.9.1 and Maven 3.10.0 archives have pinned SHA-256s checked on the host and again inside
each consumer container. They are mounted read-only and expanded into private tmpfs storage.

Every phase gets its own disposable Linux container:

| Phase | Network | Purpose |
| --- | --- | --- |
| `signatures` | `none` | Import public keys and verify tag/artifact signatures without keyserver auto-retrieval. |
| `warm` | Docker bridge | Resolve lazy build/test dependencies by executing small public consumers. This traffic is outside the isolated measurement. |
| `isolated` | `none` | Run warmed consumers offline while recording network syscalls from JVMs and descendants. |

Containers run as UID/GID `10001:10001`, with a read-only root, all capabilities dropped,
`no-new-privileges`, two CPUs, 3 GiB memory and a 512-process limit. Writable paths are private
`/work` and `/tmp` tmpfs storage plus the phase's evidence directory; warmup may also write its
fresh dependency seed. Inputs, runner scripts and the measured phase's seed are read-only.
No developer home, `.git`, SSH directory, Docker socket or publisher credentials are mounted.
The launcher records the actual image, user, network, limits, environment variable names and
mount destinations/access modes before starting each container, then removes only that container.

The warm phase creates fresh tool homes, not copies of the developer's Gradle/Maven settings.
Only those homes become the isolated phase's seed. Projects and test outputs remain separate.
The seed is a tar checkpoint containing regular files/directories under `.gradle` and `.m2`;
imports reject links, traversal, other roots and excessive expanded size. A later retry can restore
already resolved public dependencies, but must still execute both reference and sharded warmup for
every framework. A cached plugin JAR alone does not establish that Spock's Groovy compiler or
TestNG's lazy runtime is available offline. Measured executions still run in fresh projects.
Earlier failed warmup evidence can be preserved in `attempts/`.
Resolved Gradle implementation, Maven adapter and core JAR hashes must match the registry downloads.

## Scenarios and attribution

The audit requires four separate consumers:

| Tool / framework | Native runner | Reference observations | Fixture |
| --- | --- | --- | --- |
| Gradle / Jupiter | JUnit Platform, `junit-jupiter` engine | 8 | Four Java classes, two parameter rows each. |
| Gradle / Spock | JUnit Platform, `spock` engine | 8 | Four Groovy specifications, two unrolled iterations with equal input values each. |
| Gradle / TestNG | TestNG with a native `ITestListener` | 8 | Four Java classes, two data-provider invocations with equal input values each. |
| Maven / Jupiter | Surefire and Failsafe | 16 | Four unit-test and four integration-test classes, two parameter rows each. |

Each consumer runs an unapplied-plugin reference, two shards and four shards in fresh project
directories: **28 measured runs and eight baseline-versus-shards comparisons**. Online warmup
executes a reference and one active shard for each consumer, separately from the measured runs.
Native engine identity/outcome multisets must match the reference, including multiplicity.
The verifier checks the requested listener and engine, rejects repeated identities, and requires
distinct ownership across nodes. Equal Spock/TestNG input values must retain distinct native
iteration/invocation identities. Native XML totals and successful outcomes must agree with the
event stream; the current published-artifact fixtures expect no failures or skips.
Maven binds disjoint `unit-durations.tsv` and `integration-durations.tsv` histories to the corresponding
executions and explicitly configures native class patterns. A shared history can legitimately assign
one suite's families to another node and leave an empty runner; that is a different fixture contract.

The Gradle reference declares the plugin with `apply false` to prepare comparable resolution inputs;
it does not apply ShardPilot. Maven's reference leaves its ShardPilot profile disabled. These are
controls for the tested build paths, not proof that resolution can never load plugin-related code.

`strace -f -e trace=network` follows threads and descendant JVMs. Raw traces are retained, including
failed calls. Summaries classify destination-bearing `connect`, `sendto`, `sendmsg` and `sendmmsg`
calls; loopback/Unix IPC are distinguished from non-loopback IP attempts. Port-53 resolver attempts
are recorded even when the resolver itself is on loopback. Unparsed IP destinations
fail analysis. IPv4-mapped IPv6 destinations retain their socket form and a normalized IP identity.

Before consumer execution, an independent Java control attempts TCP to the reserved documentation
address `198.51.100.1:443`. The connection must be blocked and present in the trace. A tracer that
cannot observe this attempt cannot produce a passing audit. Any non-loopback IP attempt in a measured
consumer requires investigation and fails the automated check, even if the OS blocked it.

The trace covers these Linux JVM processes and their descendants. It is not a Java call-stack
attribution mechanism and does not establish universal absence of telemetry. This fixture exercises
application/configuration and sharded Gradle Jupiter/Spock/TestNG execution, plus Maven Jupiter
through both runners; it does not exercise every refresh/report/error path, Maven Spock/TestNG
consumer, native library mechanism or environment.

## Reproduce and inspect

Requirements: Python 3.12+, Git with the audited release tag and a running Linux Docker engine. No publication
credentials, repository writes, workflow dispatch, commit or push are required.

```shell
python3 -B scripts/run_published_artifact_audit.py
# Explicitly select an already published stable release:
python3 -B scripts/run_published_artifact_audit.py --version 0.5.0
```

The launcher, registry-download CLI and host-side fixture runner all take their default from
`security/published-artifact-audit.json`. The launcher passes the selected version explicitly to
each sandbox; the repository policy is not mounted there. An explicit `--version 0.4.0` still
audits that older release against its own signed tag without changing the default policy.

On Windows use an installed Python executable. A different output directory can preserve an earlier
cohort. `--reuse-downloads` explicitly reuses locally retained registry/tag bytes after checking artifact
hashes; `--skip-build` reuses an existing audit image. `--phase signatures`, `--phase warm` and
`--phase isolated` support inspecting individual stages; a fresh complete invocation is the normal gate.

Recheck retained evidence without Docker/network, including original native XML, identity/outcome
multisets, per-node ownership, network traces, signature cohort and registry-byte hashes:

```shell
python3 -B scripts/run_published_artifact_audit.py --summarize-only
python3 -B testing/published-artifacts/check_evidence.py build/published-artifact-audit
```

The second command mutates private temporary copies: lost/duplicated invocations, swapped outcomes,
blocked outbound calls, inconsistent XML, changed JAR bytes and altered signing/tag evidence must
be rejected. Five controls apply to each of the four consumers, plus four registry/signing controls:
**24 required negative controls**. Missing or duplicated control names cannot satisfy the CI gate.
Rejection removes an earlier green verification report. Original evidence is retained.

Current execution evidence uses `executionSchemaVersion: 2` and explicitly names the framework.
The verifier rejects missing framework cohorts, incomplete warmup, legacy Jupiter-only evidence
and success flags that disagree with raw files. Historical 14-run/four-comparison/nine-control
records below remain evidence of their original scope. Recheck their original archives with the
tooling retained inside those archives; they cannot satisfy the expanded current gate.

The helper tests run first and require neither Docker nor network:

```shell
python3 -B -m unittest discover -s testing/published-artifacts -p 'test_*.py' -v
```

Evidence stays under ignored `build/published-artifact-audit/`:

- `registry-verification.json`, downloaded artifacts/sidecars/signatures and `source-tag.json`;
- `signatures/signatures.json` and individual GnuPG status logs;
- each phase's `container-policy.json` and `container.log`;
- `isolated/<tool>/<framework>/<cohort>/`: original engine TSV, native XML, build log and raw syscall trace;
- the positive-control log/trace, isolated summary and final `audit-summary.json`.
- `verified-evidence.json` from independent re-reading and `negative-controls.json` from mutations.
- `audit-request.json`, the selected version/tag and reviewed publisher identity;
- each phase's `tool-versions.json` with its observed JVM, Python, GnuPG and tracer versions.

Retain the complete evidence cohort with its version, source commit and environment. Zero observed
attempts applies only to the measured scenarios and is separate from dependency vulnerability analysis.

## Full audit in GitHub Actions

The separate [Published artifact audit workflow](../.github/workflows/published-artifact-audit.yml)
supports `workflow_dispatch` and `workflow_call`; it does not run on every ordinary branch push.
The fast audit guard tests remain in the normal compatibility workflow.

From **Actions → Published artifact audit → Run workflow**, select the tooling revision and optionally
enter an already published stable version. An empty version uses the reviewed policy baseline.
The workflow must first be present on the default branch for manual dispatch to be available.
The equivalent manual command is:

```shell
gh workflow run published-artifact-audit.yml --repo sjacek/shardpilot --ref main -f version=0.5.0
```

The workflow checks out the triggering tooling SHA with complete tag history, downloads public
registry artifacts afresh and creates fresh tool homes. It runs signature verification, online
dependency warmup, 28 measured offline runs across four consumers, independent evidence verification
and all 24 evidence mutation controls. There is no restored dependency/image cache, publisher environment,
inherited release secret or credentialed upload. Build-tool downloads and the reviewed Docker base
are pinned; each phase records its actual image and tool versions. On Linux, only disposable phase
output/seed directories are made writable to UID 10001; registry inputs stay mounted read-only.

Both publishers in [publish.yml](../.github/workflows/publish.yml) now require this reusable audit
as well as tag validation and the entire compatibility workflow. Audit and compatibility can run
in parallel after tag validation. Failed, cancelled or skipped prerequisites block publication.
No new required branch-check context is necessary: the audit is a release-job dependency.

**Pre-publication scope:** a new candidate cannot be downloaded from both registries before it has
been published. The pre-release gate therefore audits the last complete **published baseline**, not
the candidate. The policy currently binds `0.5.0` to commit
`dacbfbdb07e61f37ffa524fb286a97483c6c93e9` and the reviewed publisher fingerprint. Candidate behavior
is checked by the compatibility matrix and signed-bundle verification. After both registries expose
a new release, run the audit explicitly for that version before updating the baseline policy in a
reviewed change. Do not advance the baseline to an unpublished candidate or move an existing tag.
Update the baseline regression in `testing/published-artifacts/test_audit.py` together with the policy.
Retain a compact evidence record under `docs/evidence/` that binds the successful published audit
to the version, source commit, publisher identity and original report archive. Keep older records
and their original request/policy snapshots unchanged.
Different artifact layouts/API or Java-target changes require reviewing the fixture/checks as well.

The packaging and upload steps use `always()`. They retain one versioned run artifact for **30 days**:

- `published-artifact-audit-report.zip` and its SHA-256;
- the current `ci-report.json` verdict and human-readable `ci-summary.md`;
- registry bytes, checksums/signatures, source-tag identity, original XML/event streams,
  raw `strace` logs, positive-control evidence, container policies and preparation/failure logs;
- a snapshot of the audit tooling and reviewed policy used by the run.

Dependency seeds, downloaded build-tool archives, developer homes and unrelated files are excluded.
A failed audit still produces failure/partial diagnostics; a stale phase success flag cannot turn
that run's CI verdict green. A successful exit without the complete evidence and mutation controls
fails packaging verification. Forced runner termination can prevent final steps, so `always()` is
best-effort retention rather than a guarantee after runner loss. Existing immutable release tags
do not acquire new workflows; this gate applies to future tags containing these changes.

## Local Gradle framework extension: 13 November 2026

The expanded harness passed against public **0.5.0** bytes in local Linux Docker containers.
This was a working-tree validation based on main commit
`bbb0dd0d04fcebde0ece85aa20bdea6395cd9ae5`, not a GitHub Actions run. The published source remains
`dacbfbdb07e61f37ffa524fb286a97483c6c93e9`; no release artifact or tag was changed.

| Check | Observed result |
| --- | --- |
| Framework execution | Gradle Jupiter, Spock and TestNG plus Maven Jupiter/Surefire/Failsafe: all 28 measured runs passed offline. |
| Equivalence | All eight two/four-shard comparisons matched native identities, occurrence counts and successful outcomes; native XML totals agreed. |
| Network | Zero non-loopback IP or DNS attempts in measured consumers; the separate blocked tracer control was recorded. |
| Evidence rejection | All 24 mutations were rejected; stale green verification/control reports were removed on failure. |
| Registry identity | Fifteen registry files and ten OpenPGP signatures passed; the signed tag and reviewed publisher identity matched. |
| Fast guards | 48 audit tests and 97 script tests passed on Linux; immutable dependency policy remained at 378 Gradle, 984 Maven and 44 Action records. |

The [local evidence record](evidence/published-0.5.0-gradle-frameworks-local-2026-11-13.json)
binds the result to tooling file hashes, container image, registry hashes, original event/trace
hashes and the report archive checksum. The archive remains under
`build/published-artifact-audit-gradle-frameworks-report.zip`; it is not a hosted CI artifact.
After pushing these changes, run the existing full audit workflow to establish hosted evidence
for the expanded scope. This validation is not a performance benchmark; that remains deferred
until dev is ready.

## Published 0.5.0 baseline: 13 November 2026

[Run 43446236408](https://github.com/sjacek/shardpilot/actions/runs/43446236408) completed successfully
after both registries exposed **0.5.0**. Its tooling and audited source commit are
`dacbfbdb07e61f37ffa524fb286a97483c6c93e9`; signed tag `v0.5.0` has tag-object ID
`b0c8d1fc51614857a6085b177c4ef64d1bec9bc7`. The
[retained compact evidence](evidence/published-0.5.0-2026-11-13.json) records the registry hashes,
signer, native/event/trace evidence hashes, isolation settings and nine rejected mutations.

| Check | Observed result |
| --- | --- |
| Registry and signatures | 15 files: nine Central files and six Portal files; nine Central signatures plus the signed tag verified with the unchanged reviewed publisher key. |
| Isolated Gradle | Reference: eight native executions; two nodes: 4 + 4; four nodes: 2 + 2 + 2 + 2. |
| Isolated Maven | Surefire/Failsafe reference: sixteen executions; two nodes: 8 + 8; four nodes: 4 + 4 + 4 + 4. |
| Equivalence | All four identity/multiplicity/outcome comparisons passed. |
| Network | Zero non-loopback IP or DNS attempts in fourteen measured runs; the deliberate blocked control attempt was recorded. |
| Independent recheck | Downloaded report archive checksums matched; local re-reading passed, including rejection of all nine private-copy evidence mutations. |

The run artifact is `published-artifact-audit-43446236408-1`, ID `14049180349`, with outer ZIP
SHA-256 `8cd6ad36818bf0f28a8c7a66668cc30d25370efdb9d4e2bc9a587e45e0589f7a`.
Its inner `published-artifact-audit-report.zip` has SHA-256
`f21e61f2e1b9a39cdf41e7d39ddc19205029455e88ae41827fbad34a8383729d`.
Download the full raw reports from the run before the 30-day retention expires if longer-term
review is needed; the committed compact record does not contain the raw traces or registry JARs.

This post-publication result supports advancing the default baseline to 0.5.0. The run itself
explicitly selected 0.5.0 while its tooling policy still defaulted to 0.4.0; that historical
`auditRequest` and tooling snapshot remain unchanged in the evidence record. Future audit runs
from a revision containing the updated policy use 0.5.0 when no version is supplied. Existing
immutable tags retain their original policy. The bounded-scenario and authentication limits
above still apply; this audit does not replace dependency vulnerability scans.

## Local result: 12 November 2026

This historical **0.4.0** [compact evidence](evidence/published-0.4.0-2026-11-12.json) binds the public release
to commit `58daef5436a01295ce46feea56aa307c41bbd0a0`. This was a local maintainer execution on
Windows with Linux Docker containers, not a hosted CI run or independent third-party assessment.

| Check | Observed result |
| --- | --- |
| Registry cohort | 15 files downloaded: nine from Maven Central and six from Plugin Portal. |
| Central integrity | All nine files match both SHA-256 and SHA-512 registry sidecars. |
| OpenPGP | Nine Central artifact signatures and the annotated tag signature verified; all use primary fingerprint `0AE0A30C093FDE0EA02E7502ADF2B8E6FA34B930`. |
| Portal integrity | Marker points to the expected implementation; module SHA-256s match all three JARs. Detached signatures and SHA-256/SHA-512 sidecars are absent. |
| Source comparison | Core: 10 Java files; Maven adapter: 8; bundled Gradle sources: 26. All match the tag after line-ending normalization. |
| Isolated Gradle | Seven successful offline runs; reference has eight native observations. Two/four-node unions match it exactly. |
| Isolated Maven | Seven successful offline runs, each exercising Surefire/Failsafe; reference has sixteen native observations. Two/four-node unions match it exactly. |
| Network observation | Zero non-loopback IP or DNS attempts in the fourteen measured consumer runs. Local JVM/daemon IPC remains visible in raw traces. |
| Positive control | Deliberate TCP attempt to `198.51.100.1:443` was blocked and recorded. |
| Independent checks | Native XML, original event streams, per-node identities and raw traces were re-read on the host. All four multiset comparisons passed. |
| Negative evidence controls | Nine private-copy mutations were rejected, including lost/duplicated invocations, changed outcomes/JARs/signers/tag payloads, inconsistent XML and a blocked outbound attempt. Earlier green verification reports were invalidated on rejection. |
| Tooling guards | 17 unit regressions passed on Windows/Python 3.12.14 and Linux/Python 3.12.16; workflow syntax and immutable-dependency policy checks passed. |

The first warmup harness shared timing history between Surefire and Failsafe, legitimately leaving
one runner empty on a node; the XML-copy assumption rejected that preparation. The corrected
measured fixture uses separate histories. Those failed preparation logs are retained in `attempts/`;
canonical execution checks read only the fresh `isolated/` cohort.

The observed lack of outbound attempts is limited to the authored scenarios and these artifact bytes.
The Portal's unsigned metadata and source checks remain distinct from Central's OpenPGP evidence.

## Hosted baseline result: 13 November 2026

[Run 43342517533](https://github.com/sjacek/shardpilot/actions/runs/43342517533), manually dispatched
with tooling commit `8f618ae345791b22972b4d25124f2c106631b084`, completed successfully for the
published **0.4.0** baseline. The workflow retains its verdict, report archive, registry bytes,
traces and mutation-control evidence as described above. Download those artifacts before their
30-day retention expires if longer-term independent review is needed.

The 0.5.0 publication gates also audited 0.4.0, since the candidate was not yet available in both
registries. That older audit did not establish the behavior of the new release; the separate
post-publication 0.5.0 result above supplies its registry and execution evidence.
