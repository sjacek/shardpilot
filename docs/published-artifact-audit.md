# Published artifact and isolated execution audit

[Security boundaries](security.md) | [Review follow-up](security-review-2026-11-12.md) |
[Dependency integrity](dependency-verification.md)

This maintainer check assesses public **0.4.0** distribution bytes, separately from the development
checkout and locally built release candidates. It is bounded execution evidence, not an independent
security certification, a reproducible-binary-build proof or a guarantee about every consumer.

## Registry and source checks

`scripts/published_artifacts.py` downloads the nine Maven Central release files (parent POM plus
core/adapter POMs and binary/source/Javadoc JARs), the Gradle marker POM, implementation POM/module
metadata and its three JARs. Downloads retain original/final HTTPS URLs, sizes and local SHA-256s.

- Maven Central SHA-256/SHA-512 sidecars are compared with the downloaded bytes. Its nine detached
  OpenPGP signatures are checked separately using a fresh public-only GnuPG home.
- The Gradle marker must point to `io.github.sjacek:shardpilot:0.4.0`. Module metadata's artifact
  SHA-256s must match the actual binary/source/Javadoc JARs. Absent Portal sidecars/signatures are
  recorded as absent, rather than presented as independently verified signatures.
- Java sources in each source JAR must match the appropriate modules of `v0.4.0`; only CRLF/LF
  differences are normalized. Missing/extra/changed Java sources fail verification. ZIP entries
  are checked for duplicates, traversal, absolute paths, symlinks and excessive expansion.
- Published class-file versions must not exceed Java 17. Source comparisons do not prove that
  those exact sources produced every compiled byte; no reproducible build claim is made.
- The public tag object must resolve to the same source commit. Its detached payload/signature
  is checked locally along with the artifact signatures. Public keys come from
  [the publisher's GitHub public-key endpoint](https://github.com/sjacek.gpg). Record the actual
  signing/primary fingerprints; this is not independent, out-of-band identity authentication.

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
imports reject links, traversal, other roots and excessive expanded size. Completed Gradle warmup
can reuse its verified public implementation JAR checkpoint after a later harness failure. Measured
executions still run in fresh projects. Earlier failed warmup evidence can be preserved in `attempts/`.
Resolved Gradle implementation, Maven adapter and core JAR hashes must match the registry downloads.

## Scenarios and attribution

The authored fixture has four JUnit class families, each with two parameter rows. Gradle's native
task observes eight executions. Maven runs Surefire and Failsafe, observing sixteen in total.
Each consumer runs an unapplied-plugin reference, two shards and four shards in fresh project
directories. Native engine identity/outcome multisets must match the reference, including multiplicity.
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
application/configuration and sharded Jupiter execution, including Maven's two runners; it does not
exercise every refresh/report/error path, Spock/TestNG consumer, native library mechanism or environment.

## Reproduce and inspect

Requirements: Python 3.12+, Git with the `v0.4.0` tag and a running Linux Docker engine. No publication
credentials, repository writes, workflow dispatch, commit or push are required.

```shell
python3 -B scripts/run_published_artifact_audit.py
```

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
be rejected. Rejection removes an earlier green verification report. Original evidence is retained.

The helper tests run first and require neither Docker nor network:

```shell
python3 -B -m unittest discover -s testing/published-artifacts -p 'test_*.py' -v
```

Evidence stays under ignored `build/published-artifact-audit/`:

- `registry-verification.json`, downloaded artifacts/sidecars/signatures and `source-tag.json`;
- `signatures/signatures.json` and individual GnuPG status logs;
- each phase's `container-policy.json` and `container.log`;
- `isolated/<tool>/<cohort>/`: original engine TSV, native XML, build log and raw syscall trace;
- the positive-control log/trace, isolated summary and final `audit-summary.json`.
- `verified-evidence.json` from independent re-reading and `negative-controls.json` from mutations.

Retain the complete evidence cohort with its version, source commit and environment. Zero observed
attempts applies only to the measured scenarios and is separate from dependency vulnerability analysis.

## Local result: 12 November 2026

The [retained compact evidence](evidence/published-0.4.0-2026-11-12.json) binds the public release
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
