package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static io.github.sjacek.shardpilot.ExecutionSelectionFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same selected classes are insufficient: decision, original bytes, partition and optional C3 must agree. */
class ExecutionSelectionTest {
    @TempDir
    Path directory;

    @BeforeEach
    void fixture() throws Exception {
        prepare(directory);
        bindNodes(directory, legacy());
    }

    @Test
    void bothGenerationsOfC2RemainValidAndV2RetainsTheExactReplayedProvenance() throws Exception {
        var old = new ExecutionEvidence.Inputs(legacy().resolve("expected.json"), legacy().resolve("baseline.yaml"),
                legacy().resolve("selection.json"), reviewedLegacyNodes(), ExecutionEvidence.Limits.defaults());
        assertTrue(ExecutionEvidence.evaluate(old).passed());
        assertEquals(EvidenceJson.parse(Files.readString(legacy().resolve("report.json"))), EvidenceJson.parse(ExecutionEvidence.evaluate(old).json()));
        var result = ExecutionEvidence.evaluate(evidence(directory, true));
        assertTrue(result.passed(), result.json());
        assertTrue(result.json().contains("\"schemaVersion\":2"));
        var report = object(EvidenceJson.parse(result.json()));
        assertEquals(object(EvidenceJson.parse(Files.readString(directory.resolve("selection.json")))).get("provenance"), report.get("selectionProvenance"));
        String original = Files.readString(directory.resolve("selection.json"));
        ExecutionSelection.write(inputs(directory, true), directory.resolve("selection.json"));
        assertEquals(original, Files.readString(directory.resolve("selection.json")));
    }

    @Test
    void compiledBindingIsOptionalButCannotBeSilentlyAddedOrDiscarded() throws Exception {
        rejects("COMPILED_ARTIFACT_MISMATCH", false);
        ExecutionSelection.write(inputs(directory, false), directory.resolve("selection.json"));
        bindNodes(directory, legacy());
        assertTrue(ExecutionEvidence.evaluate(evidence(directory, false)).passed());
        rejects("COMPILED_ARTIFACT_MISMATCH", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "affected", "explicit"})
    void everyProfileModeRetainsItsMeaningIncludingAFullFallback(String mode) throws Exception {
        var policy = SelectionPolicyYaml.parse("schemaVersion: 1\nprofiles: {smoke-testdata: {mode: " + mode + "}}\n");
        Files.writeString(directory.resolve("policy.yaml"), SelectionPolicyYaml.format(policy));
        var eligible = ClassManifest.read(directory.resolve("baseline.yaml"), false);
        var git = GitChanges.notRequired();
        Set<String> explicit = mode.equals("explicit") ? Set.of("example.AlphaIT") : Set.of();
        Path request = mode.equals("explicit") ? directory.resolve("request.yaml") : null;
        if (request != null) ClassManifest.write(request, explicit);
        String history = Files.readString(directory.resolve("history.tsv"));
        ProfileSelection.prepare(directory.resolve("snapshot"), policy,
                new SelectionContext(PROFILE, eligible, git.changes(), DurationHistory.parse(history), explicit), git, SUITE, history, 2);
        var prior = inputs(directory, false);
        ExecutionSelection.write(new ExecutionSelection.Inputs(prior.snapshotDirectory(), prior.policyFile(), prior.eligibilityFile(),
                prior.historyFile(), request, PROFILE, REVISION, SUITE, 2, null), directory.resolve("selection.json"));
        var root = object(EvidenceJson.parse(Files.readString(directory.resolve("selection.json"))));
        assertEquals(mode.equals("full") ? "full" : "explicit", root.get("mode"));
        var metadata = object(root.get("provenance"));
        assertEquals(mode.toUpperCase(java.util.Locale.ROOT), metadata.get("profileMode"));
        assertEquals(Files.readString(directory.resolve("snapshot/selection.id")).strip(), metadata.get("cohortSha256"));
        var plans = new java.util.TreeMap<String, String>();
        object(metadata.get("planSha256")).forEach((key, value) -> plans.put(key, (String) value));
        var parsed = ExecutionSelection.validate(object(EvidenceJson.parse(Files.readString(directory.resolve("selection.json")))),
                Files.readAllBytes(directory.resolve("selection.json")), Set.of("example.AlphaIT", "example.BetaIT", "example.UnrelatedIT"),
                REVISION, SUITE, 2, sha(directory.resolve("baseline.yaml")), plans, 100, null);
        assertEquals(mode.equals("explicit") ? Set.of("example.AlphaIT") : Set.of("example.AlphaIT", "example.BetaIT", "example.UnrelatedIT"), parsed.selected());
    }

    @Test
    void aFrozenKnownGitHeadCannotBeBoundToAnotherTrustedRevision() throws Exception {
        Path gitFile = directory.resolve("snapshot/decision/git.json");
        var root = object(EvidenceJson.parse(Files.readString(gitFile)));
        root.put("baseRevision", "b".repeat(40)); root.put("headRevision", "c".repeat(40));
        root.put("availability", "KNOWN"); root.put("source", "EXPLICIT"); root.put("failure", "NONE");
        Files.writeString(gitFile, EvidenceJson.write(root));
        var error = assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, false), directory.resolve("selection.json")));
        assertTrue(error.getMessage().contains("SELECTION_PROVENANCE_MISMATCH"));
        assertFalse(Files.exists(directory.resolve("selection.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "Full", "foreign/path"})
    void v2SuiteValidationIsNotWeakenedByTheBroaderLegacyCohortIdentifier(String suite) throws Exception {
        ExecutionSelection.write(inputs(directory, false), directory.resolve("selection.json"));
        var selection = object(EvidenceJson.parse(Files.readString(directory.resolve("selection.json"))));
        object(selection.get("provenance")).put("suite", suite);
        Files.writeString(directory.resolve("selection.json"), EvidenceJson.write(selection));
        var expected = object(EvidenceJson.parse(Files.readString(directory.resolve("expected.json"))));
        object(expected.get("cohort")).put("suite", suite);
        object(expected.get("selection")).put("sha256", sha(directory.resolve("selection.json")));
        Files.writeString(directory.resolve("expected.json"), EvidenceJson.write(expected));
        rejects("INVALID_EVIDENCE", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "history", "git", "baseline"})
    void changingAnInputWithoutChangingSelectedFamiliesStillChangesTheC2Digest(String input) throws Exception {
        String original = sha(directory.resolve("selection.json"));
        switch (input) {
            case "policy" -> Files.writeString(directory.resolve("policy.yaml"), Files.readString(directory.resolve("policy.yaml")) + "    budget: {maxFamilies: 4}\n");
            case "history" -> Files.writeString(directory.resolve("history.tsv"), Files.readString(directory.resolve("history.tsv")).replace("\t10\n", "\t10.0\n"));
            case "baseline" -> Files.writeString(directory.resolve("baseline.yaml"), "# retained native baseline\n" + Files.readString(directory.resolve("baseline.yaml")));
            case "git" -> {
                // Two unknown change sources select the same mandatory smoke set, but provenance differs.
                var policy = SelectionPolicyYaml.read(directory.resolve("policy.yaml"));
                var git = new GitChanges.Result(SelectionContext.ChangeSet.unknown(), GitChanges.Source.EXPLICIT, GitChanges.Failure.NO_BASE);
                ProfileSelection.prepare(directory.resolve("snapshot"), policy, new SelectionContext(PROFILE,
                        ClassManifest.read(directory.resolve("baseline.yaml"), false), git.changes(),
                        DurationHistory.parse(Files.readString(directory.resolve("history.tsv"))), Set.of()), git, SUITE,
                        Files.readString(directory.resolve("history.tsv")), 2);
            }
            default -> fail(input);
        }
        if (!input.equals("git")) snapshot(directory);
        ExecutionSelection.write(inputs(directory, true), directory.resolve("selection.json"));
        assertNotEquals(original, sha(directory.resolve("selection.json")));
        // The independently retained old request may not adopt this producer's new digest.
        assertFalse(ExecutionEvidence.evaluate(evidence(directory, true)).passed());
        var expected = object(EvidenceJson.parse(Files.readString(directory.resolve("expected.json"))));
        object(expected.get("selection")).put("sha256", sha(directory.resolve("selection.json")));
        if (input.equals("baseline")) object(expected.get("selection")).put("eligibilitySha256", sha(directory.resolve("baseline.yaml")));
        Files.writeString(directory.resolve("expected.json"), EvidenceJson.write(expected));
        assertFalse(ExecutionEvidence.evaluate(evidence(directory, true)).passed(), "old producer nodes must still be rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "suite", "shardCount", "planSha256", "eligibilitySha256", "engineVersion", "extra", "missing", "families", "noncanonical"})
    void independentlyRepinnedMalformedOrForeignSelectionCannotBypassValidation(String field) throws Exception {
        Path selection = directory.resolve("selection.json");
        var root = object(EvidenceJson.parse(Files.readString(selection)));
        var metadata = object(root.get("provenance"));
        switch (field) {
            case "revision" -> metadata.put(field, "b".repeat(40));
            case "suite" -> metadata.put(field, "foreign-testdata");
            case "shardCount", "engineVersion" -> metadata.put(field, 4);
            case "eligibilitySha256" -> metadata.put(field, "0".repeat(64));
            case "planSha256" -> metadata.put(field, Map.of("1", "0".repeat(64), "2", "0".repeat(64)));
            case "extra" -> metadata.put("PRIVATE_EXTRA", "PRIVATE_PAYLOAD");
            case "missing" -> metadata.remove("gitSha256");
            case "families" -> root.put(field, List.of("example.ForeignIT"));
            case "noncanonical" -> { }
            default -> fail(field);
        }
        Files.writeString(selection, EvidenceJson.write(root) + (field.equals("noncanonical") ? "\n" : ""));
        repinSelection();
        var result = ExecutionEvidence.evaluate(evidence(directory, true));
        assertFalse(result.passed(), result.json());
        assertFalse(result.json().contains("PRIVATE"));
        assertTrue(result.json().contains("\"selectionProvenance\":null"));
    }

    @Test
    void anotherValidPartitionOfTheSameCohortCannotReplaceTheFrozenC5Plan() throws Exception {
        var expected = object(EvidenceJson.parse(Files.readString(directory.resolve("expected.json"))));
        var nodes = list(expected.get("expectedNodes"));
        Object first = object(nodes.get(0)).get("planSha256");
        object(nodes.get(0)).put("planSha256", object(nodes.get(1)).get("planSha256"));
        object(nodes.get(1)).put("planSha256", first);
        Files.writeString(directory.resolve("expected.json"), EvidenceJson.write(expected));
        rejects("SELECTION_PROVENANCE_MISMATCH", true);
    }

    @ParameterizedTest
    @CsvSource({"missing,SELECTION_PROVENANCE_MISMATCH", "extra,SELECTION_PROVENANCE_MISMATCH",
            "malformed,INVALID_EVIDENCE", "non-object,INVALID_EVIDENCE"})
    void everyPlanEntryMustBePresentAndWellFormedBeforeAnyNodeIsRead(String mutation, String reason) throws Exception {
        Path selection = directory.resolve("selection.json");
        var document = object(EvidenceJson.parse(Files.readString(selection)));
        var metadata = object(document.get("provenance")); var plans = object(metadata.get("planSha256"));
        switch (mutation) {
            case "missing" -> plans.remove("2");
            case "extra" -> plans.put("3", plans.get("2"));
            case "malformed" -> plans.put("2", "PRIVATE_INVALID_HASH");
            case "non-object" -> metadata.put("planSha256", List.of(plans.get("1"), plans.get("2")));
            default -> fail(mutation);
        }
        Files.writeString(selection, EvidenceJson.write(document)); repinSelection();
        // A trusted digest is necessary, but cannot make a malformed/mismatched plan contract acceptable.
        var result = ExecutionEvidence.evaluate(evidence(directory, true));
        assertFalse(result.passed()); assertTrue(result.json().contains(reason), result.json());
        var report = object(EvidenceJson.parse(result.json()));
        assertNull(report.get("selectionProvenance")); assertNull(report.get("totals"));
        assertEquals(List.of(), report.get("nodes")); assertFalse(result.json().contains("PRIVATE"));
    }

    @Test
    void fourShardBindingIncludesEmptyPlansWithoutRelaxingTheNativeEmptyShardPolicy() throws Exception {
        var prior = inputs(directory, true); String history = Files.readString(directory.resolve("history.tsv"));
        var eligible = ClassManifest.read(prior.eligibilityFile(), false); var git = GitChanges.notRequired();
        ProfileSelection.prepare(prior.snapshotDirectory(), SelectionPolicyYaml.read(prior.policyFile()),
                new SelectionContext(PROFILE, eligible, git.changes(), DurationHistory.parse(history), Set.of()), git, SUITE, history, 4);
        var four = new ExecutionSelection.Inputs(prior.snapshotDirectory(), prior.policyFile(), prior.eligibilityFile(),
                prior.historyFile(), null, PROFILE, REVISION, SUITE, 4, prior.compiled());
        ExecutionSelection.write(four, directory.resolve("selection.json"));
        var expected = object(EvidenceJson.parse(Files.readString(directory.resolve("expected.json"))));
        object(expected.get("selection")).put("sha256", sha(directory.resolve("selection.json")));
        expected.put("shardCount", 4); expected.put("allowEmptyShards", true);
        var jobs = new ArrayList<Object>(list(expected.get("expectedNodes")));
        for (int index = 1; index <= 4; index++) {
            var node = object(EvidenceJson.parse(Files.readString(directory.resolve("nodes/node-" + Math.min(index, 2) + "/evidence.json"))));
            object(node.get("selection")).putAll(object(expected.get("selection")));
            object(node.get("shard")).put("count", 4); object(node.get("shard")).put("index", index);
            if (index > 2) {
                Path nodeDirectory = directory.resolve("nodes/node-" + index); Files.createDirectories(nodeDirectory);
                ClassManifest.write(nodeDirectory.resolve("plan.yaml"), Set.of());
                object(node.get("producer")).put("jobId", "example-job-" + index);
                object(node.get("selection")).put("planSha256", sha(nodeDirectory.resolve("plan.yaml")));
                node.put("reports", List.of()); node.put("observed", Map.of("tests", 0, "failures", 0, "errors", 0, "skipped", 0));
                var job = new java.util.TreeMap<>(object(jobs.get(0)));
                job.put("index", index); job.put("jobId", "example-job-" + index);
                job.put("planSha256", sha(nodeDirectory.resolve("plan.yaml"))); jobs.add(job);
            }
            Files.writeString(directory.resolve("nodes/node-" + index + "/evidence.json"), EvidenceJson.write(node));
        }
        expected.put("expectedNodes", jobs); Files.writeString(directory.resolve("expected.json"), EvidenceJson.write(expected));
        var result = ExecutionEvidence.evaluate(evidence(directory, true)); assertTrue(result.passed(), result.json());
        var report = object(EvidenceJson.parse(result.json()));
        assertEquals(4, object(object(report.get("selectionProvenance")).get("planSha256")).size());
        assertEquals(new java.math.BigDecimal(2), object(report.get("totals")).get("executed"));
        var nodes = list(report.get("nodes"));
        for (int index : List.of(2, 3)) {
            var node = object(nodes.get(index)); assertEquals(java.math.BigDecimal.ZERO, node.get("plannedFamilies"));
            assertEquals(java.math.BigDecimal.ZERO, object(node.get("counts")).get("executed"));
        }
        expected.put("allowEmptyShards", false); Files.writeString(directory.resolve("expected.json"), EvidenceJson.write(expected));
        rejects("UNEXPECTED_EMPTY_SHARD", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "yaml"})
    void contractBlobHashesRemainBoundEvenWhenPointersAndSelectedFamiliesStayTheSame(String format) throws Exception {
        String path = "contracts/api." + format;
        var policy = SelectionPolicyYaml.parse("""
                schemaVersion: 2
                profiles:
                  smoke-testdata:
                    mode: affected
                    impact:
                      contracts:
                        - id: api
                          files: [contracts/api.FORMAT]
                          format: FORMAT
                          pointers: [/api]
                          action: require
                          tests: {classes: [example.AlphaIT, example.BetaIT]}
                """.replace("FORMAT", format));
        Files.writeString(directory.resolve("policy.yaml"), SelectionPolicyYaml.format(policy));
        var eligible = ClassManifest.read(directory.resolve("baseline.yaml"), false);
        String history = Files.readString(directory.resolve("history.tsv"));
        String firstHash = ""; Object firstFamilies = null;
        for (boolean whitespace : List.of(false, true)) {
            String before = format.equals("json") ? "{\"api\":1}" : "api: 1\n";
            String after = format.equals("json")
                    ? whitespace ? "{ \"api\": \"PRIVATE_CONTRACT_VALUE\" }" : "{\"api\":\"PRIVATE_CONTRACT_VALUE\"}"
                    : whitespace ? "api: PRIVATE_CONTRACT_VALUE  \n" : "api: PRIVATE_CONTRACT_VALUE\n";
            var observation = StructuredContracts.compare(format.equals("json") ? SelectionImpact.Format.JSON : SelectionImpact.Format.YAML,
                    before.getBytes(StandardCharsets.UTF_8), after.getBytes(StandardCharsets.UTF_8));
            var changes = new SelectionContext.ChangeSet(SelectionContext.Availability.KNOWN, Set.of(path), "b".repeat(40), REVISION, Map.of(path, observation));
            var git = new GitChanges.Result(changes, GitChanges.Source.EXPLICIT, GitChanges.Failure.NONE);
            ProfileSelection.prepare(directory.resolve("snapshot"), policy,
                    new SelectionContext(PROFILE, eligible, changes, DurationHistory.parse(history), Set.of()), git, SUITE, history, 2);
            ExecutionSelection.write(inputs(directory, false), directory.resolve("selection.json"));
            var selection = object(EvidenceJson.parse(Files.readString(directory.resolve("selection.json"))));
            if (!whitespace) {
                firstHash = sha(directory.resolve("selection.json")); firstFamilies = selection.get("families");
                bindNodes(directory, legacy()); assertTrue(ExecutionEvidence.evaluate(evidence(directory, false)).passed());
            } else {
                assertEquals(firstFamilies, selection.get("families")); assertNotEquals(firstHash, sha(directory.resolve("selection.json")));
                rejects("SELECTION_MISMATCH", false);
            }
            assertFalse(Files.readString(directory.resolve("snapshot/decision/git.json")).contains("PRIVATE_CONTRACT_VALUE"));
            assertFalse(Files.readString(directory.resolve("selection.json")).contains("PRIVATE_CONTRACT_VALUE"));
        }
    }

    @Test
    void matchingIndependentCompiledPinWorksAtBothPreparationAndAggregation() throws Exception {
        var prior = inputs(directory, true); var base = compiled(directory);
        var pinned = new ExecutionSelection.Compiled(base.inputs(), base.manifest(), sha(base.manifest()));
        String original = Files.readString(directory.resolve("selection.json"));
        ExecutionSelection.write(new ExecutionSelection.Inputs(prior.snapshotDirectory(), prior.policyFile(), prior.eligibilityFile(),
                prior.historyFile(), null, PROFILE, REVISION, SUITE, 2, pinned), directory.resolve("selection.json"));
        assertEquals(original, Files.readString(directory.resolve("selection.json")), "an agreeing pin must not invent a different selection");
        var evidence = evidence(directory, false);
        assertTrue(ExecutionEvidence.evaluate(new ExecutionEvidence.Inputs(evidence.expectedRequest(), evidence.eligibility(), evidence.selection(),
                evidence.nodesDirectory(), evidence.limits(), pinned)).passed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"uppercase", "short", "payload"})
    void malformedCompiledPinsRejectConfigurationWithoutRetainingTheirInput(String kind) {
        String pin = switch (kind) { case "uppercase" -> "A".repeat(64); case "short" -> "a".repeat(63); default -> "PRIVATE_PIN"; };
        var base = compiled(directory);
        var error = assertThrows(IllegalArgumentException.class, () -> new ExecutionSelection.Compiled(base.inputs(), base.manifest(), pin));
        assertEquals("C2 COMPILED_ARTIFACT_MISMATCH", error.getMessage()); assertNull(error.getCause());
    }

    @Test
    void compiledByteFailureReplacesBothReportsAndRestoringExactBytesRecoversTheSameResult() throws Exception {
        Path json = directory.resolve("out/result.json"), html = directory.resolve("out/result.html");
        var trusted = evidence(directory, true); var passed = ExecutionEvidence.writeReports(trusted, json, html);
        assertTrue(passed.passed()); Path file = directory.resolve("compiled/classes/synthetic-testdata.txt"); byte[] original = Files.readAllBytes(file);
        Files.writeString(file, "PRIVATE_CHANGED");
        var failed = ExecutionEvidence.writeReports(trusted, json, html);
        assertFalse(failed.passed()); assertTrue(Files.readString(html).contains("execution evidence: FAILED"));
        assertTrue(Files.readString(json).contains("COMPILED_ARTIFACT_MISMATCH"));
        assertNull(object(EvidenceJson.parse(failed.json())).get("totals")); assertFalse(failed.html().contains("PRIVATE_CHANGED"));
        Files.write(file, original);
        assertEquals(passed, ExecutionEvidence.writeReports(trusted, json, html), "recovery must not reseal or rewrite any trusted input");
    }

    @Test
    void sha256GitRevisionIsRetainedBySelectionCompiledManifestAndC2Cohort() throws Exception {
        String revision = "b".repeat(64); var prior = inputs(directory, true); var old = compiled(directory);
        var compiledInputs = old.inputs();
        var compiled = new ExecutionSelection.Compiled(new CompiledArtifacts.Inputs(compiledInputs.directory(), revision, SUITE,
                compiledInputs.roots(), compiledInputs.requiredDirectories(), compiledInputs.requiredFiles(), compiledInputs.limits()), old.manifest(), "");
        CompiledArtifacts.seal(compiled.inputs(), compiled.manifest());
        ExecutionSelection.write(new ExecutionSelection.Inputs(prior.snapshotDirectory(), prior.policyFile(), prior.eligibilityFile(),
                prior.historyFile(), null, PROFILE, revision, SUITE, 2, compiled), directory.resolve("selection.json"));
        for (String file : List.of("expected.json", "nodes/node-1/evidence.json", "nodes/node-2/evidence.json")) {
            Path path = directory.resolve(file); var document = object(EvidenceJson.parse(Files.readString(path)));
            object(document.get("cohort")).put("revision", revision);
            object(document.get("selection")).put("sha256", sha(directory.resolve("selection.json")));
            Files.writeString(path, EvidenceJson.write(document));
        }
        var base = evidence(directory, false);
        var result = ExecutionEvidence.evaluate(new ExecutionEvidence.Inputs(base.expectedRequest(), base.eligibility(), base.selection(),
                base.nodesDirectory(), base.limits(), compiled));
        assertTrue(result.passed(), result.json());
        assertEquals(revision, object(object(EvidenceJson.parse(result.json())).get("selectionProvenance")).get("revision"));
    }

    @Test
    void optingIntoCompiledVerificationCannotSilentlyAcceptALegacyUnboundSelection() throws Exception {
        var input = new ExecutionEvidence.Inputs(legacy().resolve("expected.json"), legacy().resolve("baseline.yaml"),
                legacy().resolve("selection.json"), reviewedLegacyNodes(), ExecutionEvidence.Limits.defaults(), compiled(directory));
        var result = ExecutionEvidence.evaluate(input);
        assertFalse(result.passed()); assertTrue(result.json().contains("COMPILED_ARTIFACT_MISMATCH"));
        var report = object(EvidenceJson.parse(result.json()));
        assertEquals(new java.math.BigDecimal(2), report.get("schemaVersion")); assertNull(report.get("selectionProvenance"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "missing", "extra", "manifest", "pin", "revision", "suite", "roots"})
    void actualCompiledBytesAndIndependentlyConfiguredIdentityAreRechecked(String mutation) throws Exception {
        var binding = compiled(directory);
        Path input = directory.resolve("compiled/classes/synthetic-testdata.txt");
        switch (mutation) {
            case "changed" -> Files.writeString(input, "PRIVATE_CHANGED");
            case "missing" -> Files.delete(input);
            case "extra" -> Files.writeString(input.resolveSibling("extra.txt"), "extra");
            case "manifest" -> Files.writeString(binding.manifest(), "PRIVATE_INVALID");
            case "pin" -> binding = new ExecutionSelection.Compiled(binding.inputs(), binding.manifest(), "0".repeat(64));
            default -> {
                var prior = binding.inputs();
                binding = new ExecutionSelection.Compiled(new CompiledArtifacts.Inputs(prior.directory(),
                        mutation.equals("revision") ? "b".repeat(40) : REVISION, mutation.equals("suite") ? "foreign-testdata" : SUITE,
                        mutation.equals("roots") ? List.of("classes", "other") : prior.roots(), prior.requiredDirectories(), prior.requiredFiles(), prior.limits()), binding.manifest(), "");
            }
        }
        var prior = evidence(directory, false);
        var result = ExecutionEvidence.evaluate(new ExecutionEvidence.Inputs(prior.expectedRequest(), prior.eligibility(), prior.selection(),
                prior.nodesDirectory(), prior.limits(), binding));
        assertFalse(result.passed()); assertTrue(result.json().contains("COMPILED_ARTIFACT_MISMATCH"), result.json());
        assertFalse(result.json().contains("PRIVATE"));
    }

    @Test
    void failedNativeProcessStillFailsAfterAllSelectionAndCompiledBindingsPass() throws Exception {
        Path node = directory.resolve("nodes/node-1/evidence.json");
        var root = object(EvidenceJson.parse(Files.readString(node)));
        object(root.get("process")).put("exitCode", 137);
        Files.writeString(node, EvidenceJson.write(root));
        rejects("NATIVE_PROCESS_FAILED", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision/decision.json", "decision/git.json", "selected.yaml", "selection.id", "policy.yaml", "history.tsv"})
    void corruptRetainedInputsInvalidateAnOldOutputWithoutLeakingTheirPayload(String name) throws Exception {
        Path input = directory.resolve(name.contains("/") || name.equals("selected.yaml") || name.equals("selection.id") ? "snapshot/" + name : name);
        Files.writeString(input, "PRIVATE_INVALID");
        var error = assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, true), directory.resolve("selection.json")));
        assertFalse(error.getMessage().contains("PRIVATE")); assertNull(error.getCause());
        assertFalse(Files.exists(directory.resolve("selection.json")));
    }

    @Test
    void oversizedInputFailsBoundedlyAndInvalidatesStaleSelection() throws Exception {
        Files.writeString(directory.resolve("policy.yaml"), "x".repeat(1_048_577));
        var failure = assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, true), directory.resolve("selection.json")));
        assertTrue(failure.getMessage().contains("RESOURCE_LIMIT"));
        assertFalse(Files.exists(directory.resolve("selection.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot/selected.yaml", "policy.yaml", "baseline.yaml", "history.tsv", "compiled-manifest.json", "compiled/classes/synthetic-testdata.txt"})
    void outputAliasesAreRejectedBeforeAnyInputIsInvalidated(String name) throws Exception {
        Path input = directory.resolve(name); String before = Files.readString(input);
        assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, true), input));
        assertEquals(before, Files.readString(input));
        Path alias = directory.resolve("alias.json"); Files.createLink(alias, input);
        assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, true), alias));
        assertEquals(before, Files.readString(input));
        var evidence = evidence(directory, true);
        if (name.startsWith("compiled")) assertThrows(IllegalArgumentException.class,
                () -> ExecutionEvidence.writeReports(evidence, alias, directory.resolve("out.html")));
        assertEquals(before, Files.readString(input));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symbolicLinksInFrozenInputsAreNeverFollowed() throws Exception {
        Path policy = directory.resolve("policy.yaml"); Files.move(policy, directory.resolve("real.yaml"));
        Files.createSymbolicLink(policy, directory.resolve("real.yaml"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionSelection.write(inputs(directory, false), directory.resolve("selection.json")));
        assertFalse(Files.exists(directory.resolve("selection.json")));
    }

    private void repinSelection() throws Exception {
        Path file = directory.resolve("expected.json"); var expected = object(EvidenceJson.parse(Files.readString(file)));
        object(expected.get("selection")).put("sha256", sha(directory.resolve("selection.json")));
        Files.writeString(file, EvidenceJson.write(expected));
    }

    private static List<?> list(@org.jspecify.annotations.Nullable Object value) {
        return assertInstanceOf(List.class, value);
    }

    private void rejects(String reason, boolean compiled) {
        var result = ExecutionEvidence.evaluate(evidence(directory, compiled));
        assertFalse(result.passed(), result.json()); assertTrue(result.json().contains(reason), result.json());
    }

    private static Path legacy() {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("docs/examples/execution-evidence-testdata"))) root = root.getParent();
        return root.resolve("docs/examples/execution-evidence-testdata");
    }

    private Path reviewedLegacyNodes() throws Exception {
        Path nodes = directory.resolve("legacy"); Files.createDirectories(nodes);
        for (int node = 1; node <= 2; node++) {
            Path source = legacy().resolve("node-" + node);
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path output = nodes.resolve("node-" + node).resolve(source.relativize(file));
                    Files.createDirectories(output.getParent()); Files.copy(file, output);
                }
            }
        }
        return nodes;
    }
}
