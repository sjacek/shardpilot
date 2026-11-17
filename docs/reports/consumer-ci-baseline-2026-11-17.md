# Full consumer CI baseline acceptance — 17 November 2026

[Acceptance guide](../consumer-ci-baseline.md) · [Earlier selector comparison](selection-migration-2026-11-15.md)

[Machine-readable summary](consumer-ci-baseline-2026-11-17.json)

This report records exact successful **scheduled full** pipelines. It replaces the local
compiled-name inventory as the native eligibility boundary for this acceptance exercise.
It does not claim execution of the new policies in service CI, publication of development
features, or a performance improvement. Benchmarking remains deferred.

## Immutable CI cohorts

| Consumer | Pipeline | Full revision | Compile job | Root shards |
| --- | --- | --- | --- | --- |
| Tichodroma | `3076935763` | `6f403eeea4c291b0c7986685d37a1cef6244a541` | `18090664764` | `18090664773`, `18090664774`, `18090664775` |
| Ops | `3077064952` | `f8172c679a62b66eaec4e3cf840dc518c2f9dff6` | `18091558499` | `18091558508`, `18091558509` |

The consumer-owned `gradle/shardpilot/ci-baseline.json` files pin pipeline/job metadata,
artifact sizes, archive hashes, coverage/history paths, native ownership and report jobs.
Archives, bytecode, resources, original XML and full API reports are retained privately.
No proprietary test classes or application resources are vendored into ShardPilot fixtures.

| Verified boundary | Tichodroma | Ops |
| --- | ---: | ---: |
| Root full eligible classes | 285 | 103 |
| Planned/actual shard classes | 95 / 94 / 96 | 53 / 50 |
| Original root XML testcase occurrences | 3108 | 1782 |
| Root XML skips | 3 | 0 |
| Root XML failures/errors | 0 / 0 | 0 / 0 |
| GitLab root display identities | 3088 | 1752 |
| Repeated XML display occurrences | 20 | 30 |
| Complete transferred compilation files | 14368 | 2851 |
| Complete transferred compilation bytes | 218772588 | 15638051 |

Compiled native candidate ownership equals full eligibility exactly. Plans partition it
without overlap; actual manifests equal their plans; original XML covers each shard's class
set. The native aggregate verification reports pass and their class-set hashes agree.
The distinct `(class, display name, result)` sets agree between XML and GitLab. Original XML
multiplicities are retained rather than treating the lower API count as missing execution.

Ops now has 103 eligible classes, compared with 92 in the earlier local diagnostic inventory.
The older comparison remains useful for its frozen scenarios; its class inventory is not
the accepted CI baseline.

## Compilation integrity and provenance

| Hash | Tichodroma | Ops |
| --- | --- | --- |
| Compile archive SHA-256 | `72bd3f5fc9dcb8b3dbf8980fa0569d005a2b085013b4199717984a6d4c229ccf` | `da8a49d0a379a9b979f14247f140c7f92dade6f745678e96eed86d8f8ecce805` |
| Full baseline SHA-256 | `f0439b2d85371a25b54d62aaaee1dcdea0e512d5ebc8dd42ea512d9145852d4d` | `a79b2e4bee39ff635570da211c185bd20fa4c8dcf4165c7ce8000097ce863c7d` |
| Measured history SHA-256 | `e2bb9ee31666d63f1801b93cac43657a4b472097eba48bb1fffbd9fc0436e6cd` | `8a0ce9622ff7537619d64d6ad66a20e2ac070e87a7dd3f4f3bd83eb7de6751d2` |

Tichodroma additionally retains an original legacy producer seal covering 12580 files;
its revision, full-suite identity, complete file set and every file hash were checked.
Ops' selected full compile job has no equivalent legacy seal; its authenticated CI identity,
archive digest and complete extracted inventory establish the retained boundary instead.

The development C3 core seals and verifies **all** transferred compilation files after
download. This is retention evidence, not a retrospective claim that historical test workers
used C3. Wrong-revision and changed-byte probes must fail; restored bytes must verify again.
Runtime JAR hashes are retained with private acceptance reports so an unpublished development
build is distinguishable from registry 0.5.0.

The private retained capture lives outside Gradle build directories, so `clean` does not
remove the originals, verified compilation tree, manifests, runtime or detailed decisions.

## Complete service policies

Both services now have service-owned `daily-testdata`, `smoke-testdata` and `full-testdata`
profiles. Policies classify the captured source layout, protect changed eligible tests and
shared guards, declare JSON/YAML contract rules, and use explicit full fallbacks for unknown
ownership or unavailable semantic analysis. Tichodroma additionally declares its four library
providers and host consumers. The daily limits remain 24 families / 900 estimated seconds
for Tichodroma and 12 / 900 for Ops, with 60 seconds for missing history and protected overflow
rejection. Estimates exclude startup/queueing and are not a runtime timeout.

Deliberate changes from the legacy selectors:

- All matching domain rules contribute, including parent and deeper probes. Shared guards
  also apply to test/resource infrastructure; documentation ignores never remove them.
- Directly changed eligible tests remain mandatory even when a legacy nightly preference
  would defer them. An oversized protected set fails without an executable partial selection.
- Unknown future domains/modules request the complete root suite rather than silently
  selecting a default probe. Missing semantic contract inputs also request full coverage.
- Ops' separate SMS E2E source set has an explicit full-root review rule. Its opt-in
  `sms-gateway:e2e` job still requires its own inputs and is not covered by root integration E.

The actual core evaluates hypothetical singleton edits for all **4684** tracked Tichodroma
paths and **1036** Ops paths, plus all **285 / 103** changed eligible test sources, full/smoke,
empty diff, new ownership, policy changes, synthetic known contract changes and a protected
overflow scenario. Every currently tracked path must have a reviewed classification.
No successful decision may contain foreign classes or omit a protected changed test.
The full profile must equal E. These are planning acceptance checks, not new native runs.

| Accepted policy matrix | Tichodroma | Ops |
| --- | ---: | ---: |
| All cases | 4693 | 1045 |
| Tracked-path subset decisions | 4677 | 1015 |
| Tracked-path explicit full decisions | 7 | 21 |
| Unclassified tracked paths | 0 | 0 |
| Changed eligible test sources protected | 285 | 103 |

The seven Tichodroma full path cases lack semantic metadata in this path-only sweep. Ops
has two such cases and nineteen explicitly full-owned SMS E2E source paths. Synthetic
known JSON/YAML whole-document changes exercise the configured guarded subsets instead.

## Validation

- Core tests passed through Gradle and Maven: 781 tests, zero failures/errors; nine
  platform-dependent cases skipped on Windows.
- Portable cohort/policy trust regressions run through the Java functional bridge, including
  actual C3 sealing, wrong revisions, altered bytes and real-core policy evaluation.
- Both service-owned test suites passed against their private CI captures, with no skipped
  core-backed check. The actual 4693 / 1045-case sweeps passed.
- Strict dependency policy passed without dependency or checksum changes. The PlantUML SVG
  is normalized and the actual offline Jekyll output passes internal-link/asset validation.

## Independent native gates and limits

Full API reports and summaries are retained with exact job bindings. Tichodroma additionally
records native unit results, the prompt pilot, dictionaries E2E, container cleanup, federation,
shared modules, communication artifact consumer and eBOK. Ops records native unit results.
Report-tool suites remain separately named. Counts exposed only through GitLab report
artifacts are display observations; unavailable original XML multiplicities are not invented.

Historical XML has no new C2 v2 producer records. No invocation IDs are synthesized from
row offsets. The selected Ops schedule did not run its separately opted-in SMS gateway E2E
job; its source set is neither part of the root baseline nor falsely covered by the C3 seal.
Do not remove these native ownership boundaries when enabling profile selection.

Next adoption gates: publish and verify the development candidate for fresh Gradle/Maven
consumers, then explicitly migrate each service's CI and replace its selector after native
C2/C3 evidence acceptance. No service CI selector was switched by this preparation.
