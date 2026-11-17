# Daily selector comparison — 15 November 2026

[Workflow and reproduction guide](../selection-migration.md)

[Machine-readable comparison, exact differences and adapter hashes](selection-migration-2026-11-15.json)

## Result and scope

**19 frozen scenarios: 15 matching decisions and 4 explained class-set differences.** No mandatory
core class was lost in an accepted ShardPilot selection. Unmapped changes and rename destinations
were rejected by both configurations. Neither service was built, tested, reconfigured or migrated.
No benchmark was run; the estimated costs below are not observed pipeline durations.

The comparison uses the actual daily `scripts/ci/integration_short.py` selectors, rather than the
older advisory `integration_selection_report.py` scripts. Both consumers were clean for tracked
files at capture. `tichodroma-svc` uses the root checkout; Ops uses the existing
`worktrees/ops-short-integration/ops-svc` checkout, which contains its daily selector.

| Consumer | Selector source revision / comparison head | Actual diff base | Compiled names | Mandatory core | Budget |
| --- | --- | --- | ---: | ---: | --- |
| tichodroma-svc | `980e6bdc43beb8e210b923e59637c48e98d28398` | `16ec904e9237ed17de11a59478ba0fdb80f9bf7e` | 285 | 8 | 24 classes / 900 estimated seconds |
| ops-svc | `c3c4a6b776251d6deba6e00a3424537b8f026a27` | `65ef67904e46f7ca13fe8f4be125cf601af4d99e` | 92 | 4 | 12 classes / 900 estimated seconds |

Eligibility is a **compiled-name inventory, not native discovery**. Existing bytecode was not
rebuilt for these revisions. The class inventory and its file hashes are frozen, but do not prove
that all tests of either service would be discovered/enabled by the native engine. The two actual
diffs retain all paths between the recorded commits. The remaining scenarios deliberately use
synthetic changed paths against the same frozen inventory/history; they are not claimed to be
historical commits. Service code and input payloads stay in ignored private `build/c53-comparison`.

The candidate declarative policies classify the exact frozen path space independently of the
legacy selector's returned class sets. Path targets, mandatory core and shared/global guards were
reviewed against the source. They are diagnostic candidates, **not complete production migration
policies**. ShardPilot's changed-test protection and optional cost ordering remain enabled; they
were not weakened to force equality.

## Frozen scenario results

| Scenario | tichodroma-svc (legacy / ShardPilot) | ops-svc (legacy / ShardPilot) |
| --- | --- | --- |
| actual-diff | 12 / 12 — MATCH | 9 / 10 — EXPLAINED |
| documentation | 8 / 8 — MATCH | 4 / 4 — MATCH |
| build-guards | 12 / 12 — MATCH | 7 / 7 — MATCH |
| domain | 9 / 9 — MATCH | 5 / 5 — MATCH |
| changed-test | 9 / 9 — MATCH | 5 / 5 — MATCH |
| changed-nightly | 8 / 9 — EXPLAINED | 4 / 5 — EXPLAINED |
| unknown-path | reject / reject — MATCH | reject / reject — MATCH |
| rename | reject / reject — MATCH | reject / reject — MATCH |
| budget | 24 / 24 — EXPLAINED | 12 / 12 — MATCH |
| module | 9 / 9 — MATCH | Not applicable |

## Explained differences

1. **Changed nightly-only tests.** The service selectors check the nightly exclusion patterns even
   for directly changed integration tests. ShardPilot v2 protects an eligible changed test before
   optional budget packing. Tichodroma therefore keeps `TestDataBasicSubaccountsExecutionIT`
   (8 → 9 selected classes); Ops keeps `TestDataExecutionRegressionIT` (4 → 5). The same Ops test
   appears in its actual diff (9 → 10). These three differences add required changed-test coverage;
   they do not drop core tests. A protected set exceeding budget must fail planning or use the
   explicit keep-required policy, rather than silently defer the changed test.
2. **Optional budget packing.** The Tichodroma stress scenario changes every configured probe
   area. Both selectors keep 8 mandatory tests and select 24 total. The legacy selector considers
   optional probes alphabetically; ShardPilot uses priority, then estimated cost, then binary name.
   Ten optional probes differ on each side. The selected estimates are **599.1 versus 240.8 seconds**
   using the same TSV. This is a policy difference, not measured acceleration or proof of equivalent
   business coverage. Consumers wanting particular expensive probes must mark them required or set
   explicit priorities. The exact old/new sets are retained in the reviewed expectations JSON.

The optional legacy-only probes in that budget scenario are AssistantPersistence, AnnouncementSchema,
PushContentResolver, TaskResolver, DpoQuestionnairePersistence, GeneratedDocumentService,
ClamAvDocumentMalwareScanner, KsefInvoiceResolver, InvoiceSubmissionEligibility and ReportStateStore
(the actual names have the `IT` suffix). The new-only probes are PatientSubaccountSlice,
PortalPreferencesRepository, LldapDirectoryUserClient, AuditRevisionActor, FeatureCatalogGrpc,
TestDataExecutionRepository, DisabilityCertificateResolver, CertificateReviewQueuePersistence,
PatientContractDocumentPersistence and DictionaryResolver. Full binary names and per-family costs,
protection and deferral reasons are in the private decision reports.

Ops has no imported measured timings in this snapshot: every family uses the explicit 60-second
unknown estimate. Its budget scenario chooses the same 12 classes. Tichodroma uses its existing
measured/missing mix with the same 60-second substitute in both tools.

### Obligations beyond the selected root classes

The module scenario chooses the same 9 Tichodroma root tests. The old report also records
`:forum:integrationTest` in `nightlyModuleTasks`. ShardPilot's module dependency mapping selects
configured tests; it does not invent or schedule a consumer's separate library/dedicated test tasks.
Keep those native nightly/full gates explicitly in the consumer's build/CI. Equality of root-class
sets is not acceptance of the consumer's complete orchestration.

## Reproducibility and integrity

The development core JAR SHA-256 is
`96288e636dfa8a44fb9eca511a82ca62e25053c8f3751c7cd2196f2f302bf983`.
It was built from the checkout based on `fc393964b274ea1b0a92ea0a572ba3945643a604`, with the
comparison probe/harness added in this working change. Its version label is 0.5.0, but it is
**not the published 0.5.0 JAR**, which lacks these development features.

| Frozen input SHA-256 | tichodroma-svc | ops-svc |
| --- | --- | --- |
| Input manifest | `b73620b750737f6045906ed3a2b0b85e9b14e4ef8516b3acc5d6a617655b3680` | `ebe659b669b97713b948ecf1da1db272fce9c315801f5c87e32e1ff8d179de1b` |
| Daily selector | `793a6d8e8ef0ff5a90b08a25bcbd6b634376a218175b479792dee3ffd2ca170d` | `5db765a0e127ec5b2ed12ad5b8a548094b69d86b1b4516c8e8f8ddb21dac9b3b` |
| Candidate policy | `6330bf976bc8744cfa270a6826d1ccd9ff818cfb3f5244c9c89dd385936712ee` | `e72f16f0a282234bb1106f69884948e8e7e11d1e76a89c8e6a97960a665aa447` |
| Eligibility | `13cb8b14679dffe82e63ed1d39f995a36aaa7a7a374de7434de5e65a1b94444f` | `85d38a60d180cc9d4ccbf431ef9f072c07fc6f2c0a897a1ec29e6c2116a8834e` |
| History TSV | `dcf05c61b86d8661084639d12e539eb47f962dbd9f71df91891648db19c206c1` | `2b9a8f7e1987ab71d9bcc676e59f87ed3a83a8b0939a89e9fa2e42b87f56b062` |

All imported script/helper, scenario and compiled-inventory bytes also have entries in the frozen
input manifest. Replay validates those entries before importing a selector. Original inputs are
never overwritten; reviewed replays use fresh directories and separate exact-set expectations.

Local retained outputs: `build/c53-comparison/<consumer>/accepted-replay/comparison.json` and
`comparison.html`, plus each scenario's original legacy JSON, core decision JSON/HTML, C5 snapshot
and two plan manifests. `provesNativeExecution` and `benchmark` are explicitly false.

Adapter parity and generic native-flow verification are recorded in the verification section of
the [testing guide](../testing.md#frozen-selector-comparison-and-complete-workflow).
All six representative adapter comparisons passed with byte-identical selection v2. The two
generic native workflows passed; the corrupted-byte Gradle run and wrong-revision Maven run both
rejected both workers before XML existed and failed the aggregate gate. The JSON summary retains
the actual exits and report outcomes. These are local Windows/JDK-25 checks; hosted CI is pending push.

## Remaining acceptance before service migration

Obtain complete native eligibility and compiled bytes from one trusted service revision, review a
complete policy covering the supported path space, accept/reprioritize optional coverage differences,
retain separate module/dedicated suites and full-run gates, then publish and verify fresh consumers.
The test harness is not a production selector dependency. Benchmarking remains deferred until dev
is ready; this report makes no CI speed or memory claims.
