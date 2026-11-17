package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/** Real byte bindings and independent trusted inputs exercise all five C2 gates and failure privacy. */
class ExecutionEvidenceTest {
    @TempDir Path directory;
    private Path nodes;
    private ExecutionEvidence.Inputs inputs;

    @BeforeEach
    void fixture() throws IOException {
        Path example = examples();
        Path trusted = directory.resolve("trusted"); nodes = directory.resolve("nodes");
        Files.createDirectories(trusted); Files.createDirectories(nodes);
        for (String name : List.of("expected.json", "baseline.yaml", "selection.json")) Files.copy(example.resolve(name), trusted.resolve(name));
        for (int index : List.of(1, 2)) {
            try (var files = Files.walk(example.resolve("node-" + index))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = nodes.resolve("node-" + index).resolve(example.resolve("node-" + index).relativize(file));
                    Files.createDirectories(target.getParent()); Files.copy(file, target);
                }
            }
        }
        inputs = new ExecutionEvidence.Inputs(trusted.resolve("expected.json"), trusted.resolve("baseline.yaml"),
                trusted.resolve("selection.json"), nodes, ExecutionEvidence.Limits.defaults());
    }

    @Test
    void authoredPositiveFixtureAndBothRenderersDescribeTheSameCompleteCohort() throws Exception {
        var result = ExecutionEvidence.writeReports(inputs, directory.resolve("out/report.json"), directory.resolve("html/report.html"));
        assertTrue(result.passed(), result.json());
        assertEquals(json(examples().resolve("report.json")), EvidenceJson.parse(result.json()));
        assertEquals(result.json(), Files.readString(directory.resolve("out/report.json")));
        assertEquals(result.html(), Files.readString(directory.resolve("html/report.html")));
        assertTrue(result.html().contains("Independent C2 gates"));
        assertEquals(result, evaluate(), "no current timestamps or hash iteration order in the logical result");
    }

    @ParameterizedTest @ValueSource(ints = {1, 137, -1073741819})
    void successfulXmlNeverOverridesAFailedSignedNativeExit(int code) throws Exception {
        var root = node(1); section(root, "process").put("exitCode", code); saveNode(1, root);
        var result = rejected("NATIVE_PROCESS_FAILED");
        assertTrue(result.json().contains("\"nativeProcess\":\"FAILED\""));
        assertTrue(result.json().contains("\"tests\":2,\"executed\":2"));
    }

    @ParameterizedTest @ValueSource(strings = {"timed-out", "cancelled", "killed", "unknown", "not-started"})
    void incompleteNativeStatesFailEvenWhenReportsAreAvailable(String status) throws Exception {
        var root = node(1); var process = section(root, "process"); process.put("state", status); process.put("exitCode", null);
        if (status.equals("not-started")) { process.put("startedAt", null); process.put("finishedAt", null); }
        saveNode(1, root); rejected("INCOMPLETE_PROCESS");
    }

    @ParameterizedTest @CsvSource({"revision,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "runId,foreign-run", "project,foreign-project", "suite,foreign-suite"})
    void rejectsForeignCohortIdentity(String field, String value) throws Exception {
        var root = node(1); section(root, "cohort").put(field, value); saveNode(1, root); rejected("COHORT_MISMATCH");
    }

    @Test
    void retryAttemptsCannotBeMixedEvenWhenCountersAndHashesMatch() throws Exception {
        var root = node(1); section(root, "cohort").put("attempt", 2); saveNode(1, root); rejected("ATTEMPT_MISMATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"job", "index", "count", "selection", "baseline", "plan"})
    void independentlySuppliedIdentityCannotBeReplacedByProducerClaims(String kind) throws Exception {
        var root = node(1);
        switch (kind) {
            case "job" -> section(root, "producer").put("jobId", "another-job");
            case "index" -> section(root, "shard").put("index", 2);
            case "count" -> section(root, "shard").put("count", 3);
            case "selection" -> section(root, "selection").put("sha256", "0".repeat(64));
            case "baseline" -> section(root, "selection").put("eligibilitySha256", "0".repeat(64));
            case "plan" -> section(root, "selection").put("planSha256", "0".repeat(64));
            default -> fail();
        }
        saveNode(1, root); assertFalse(evaluate().passed());
    }

    @ParameterizedTest @ValueSource(strings = {"2010-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "2026-11-14T09:00:02+01:00"})
    void processTimestampsMustBeUtcAndWithinTheIndependentlyRetainedJobLifetime(String timestamp) throws Exception {
        var root = node(1); section(root, "process").put("startedAt", timestamp); saveNode(1, root); rejected("INVALID_EVIDENCE");
    }

    @Test
    void missingShardProducesUnknownTotalsRatherThanAPartialSuccess() throws Exception {
        removeNode(2);
        var result = rejected("MISSING_SHARD");
        assertTrue(result.json().contains("\"totals\":null"));
        assertTrue(result.json().contains("\"nativeProcess\":\"UNKNOWN\""));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void anUnknownNodeNeverErasesAnotherNodesEstablishedNativeFailure(int failedNode) throws Exception {
        var root = node(failedNode); section(root, "process").put("exitCode", 137); saveNode(failedNode, root);
        removeNode(failedNode == 1 ? 2 : 1);
        var result = rejected("NATIVE_PROCESS_FAILED");
        var report = object(EvidenceJson.parse(result.json()));
        assertEquals("FAILED", section(report, "gates").get("nativeProcess"));
        assertNull(report.get("totals"));
        assertEquals(List.of("MISSING_SHARD", "NATIVE_PROCESS_FAILED"), report.get("reasons"));
    }

    @Test
    void corruptXmlDoesNotEraseVerifiedProcessOrPlanGates() throws Exception {
        report(1, "<testsuite");
        var result = rejected("INVALID_XML");
        var report = object(EvidenceJson.parse(result.json()));
        assertEquals(Map.of("provenance", "PASSED", "nativeProcess", "PASSED", "plannedCoverage", "PASSED",
                "reportedCoverage", "FAILED", "skipPolicy", "UNKNOWN"), report.get("gates"));
        assertNull(report.get("totals"));
    }

    @Test
    void foreignProducerCountsRemainDiagnosticAndNeverBecomeTrustedTotals() throws Exception {
        var root = node(2); section(root, "producer").put("jobId", "unrelated-job"); saveNode(2, root);
        var report = object(EvidenceJson.parse(rejected("COHORT_MISMATCH").json()));
        assertNull(report.get("totals"));
        for (Object value : list(report, "nodes")) assertNotNull(object(value).get("counts"));
        assertEquals("FAILED", section(report, "gates").get("provenance"));
    }

    @Test
    void jsonIntegerRepresentationsDoNotInventADifferentAttempt() throws Exception {
        var root = node(1); section(root, "cohort").put("attempt", new BigDecimal("1.0")); saveNode(1, root);
        assertTrue(evaluate().passed(), evaluate().json());
    }

    @Test
    void extraNodeIsRejectedEvenIfEveryExpectedNodePasses() throws Exception {
        Files.createDirectory(nodes.resolve("node-3")); rejected("UNEXPECTED_SHARD");
    }

    @ParameterizedTest @ValueSource(strings = {"index", "jobId"})
    void duplicateExpectedNodeIdentitiesAreNotHiddenBySetUnion(String field) throws Exception {
        var expected = json(inputs.expectedRequest()); var list = list(expected, "expectedNodes");
        object(list.get(1)).put(field, object(list.get(0)).get(field)); save(inputs.expectedRequest(), expected); rejected("DUPLICATE_SHARD");
    }

    @Test
    void plansMustCoverTheTrustedSelectionExactlyWithoutCrossNodeFamilyOwnership() throws Exception {
        bindSecondNodePlan(Set.of("example.AlphaIT$Nested")); rejected("PLANNED_COVERAGE_FAILED");
    }

    @Test
    void nestedBinaryClassesBelongToTheirOuterFamily() throws Exception {
        report(1, suite("example.AlphaIT$Nested", "example.AlphaIT$Nested", "one", ""));
        assertTrue(evaluate().passed(), evaluate().json());
    }

    @Test
    void foreignReportedFamilyCannotSatisfyPlannedCoverage() throws Exception {
        report(1, suite("example.OtherIT", "example.OtherIT", "one", "")); rejected("REPORTED_COVERAGE_FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"failure", "error"})
    void nativeFailureMarkersFailTheReportedGateEvenWhenProcessExitIsZero(String outcome) throws Exception {
        report(1, suite("example.AlphaIT", "example.AlphaIT", "PRIVATE_METHOD", "<" + outcome + ">PRIVATE_FAILURE</" + outcome + ">"));
        var result = rejected("REPORTED_COVERAGE_FAILED"); assertFalse(result.json().contains("PRIVATE_")); assertFalse(result.html().contains("PRIVATE_"));
    }

    @Test
    void skipPolicyAndMinimumExecutionAreIndependent() throws Exception {
        report(1, suite("example.AlphaIT", "example.AlphaIT", "one", "<skipped/>"));
        rejected("SKIP_POLICY_FAILED");
        var expected = json(inputs.expectedRequest()); expected.put("skipPolicy", "allow"); expected.put("maximumSkippedTests", 1); save(inputs.expectedRequest(), expected);
        rejected("MINIMUM_EXECUTION_FAILED");
        expected.put("minimumExecutedTests", 1); save(inputs.expectedRequest(), expected); assertTrue(evaluate().passed());
        expected.put("maximumSkippedTests", 0); save(inputs.expectedRequest(), expected); rejected("SKIP_POLICY_FAILED");
    }

    @Test
    void anExplicitSubsetKeepsFullEligibilityAndAllowsOnlyExplicitlyEmptyPlannedNodes() throws Exception {
        bindSecondNodePlan(Set.of());
        var root = node(2); root.put("reports", List.of()); root.put("observed", null); saveNode(2, root);
        Files.delete(xmlFile(2));
        String selection = "{\"families\":[\"example.AlphaIT\"],\"mode\":\"explicit\",\"schemaVersion\":1}";
        Files.writeString(inputs.selection(), selection);
        var expected = json(inputs.expectedRequest()); var selected = section(expected, "selection");
        selected.put("mode", "explicit"); selected.put("sha256", sha(inputs.selection()));
        expected.put("minimumExecutedTests", 1); expected.put("allowEmptyShards", true); save(inputs.expectedRequest(), expected);
        for (int index : List.of(1, 2)) {
            root = node(index); section(root, "selection").putAll(selected); saveNode(index, root);
        }
        assertTrue(evaluate().passed(), evaluate().json());
        expected.put("allowEmptyShards", false); save(inputs.expectedRequest(), expected); rejected("UNEXPECTED_EMPTY_SHARD");
    }

    @ParameterizedTest @ValueSource(strings = {"whitespace", "key-order", "unsorted", "duplicate", "foreign", "nested", "empty"})
    void matchingHashesCannotAuthorizeANoncanonicalOrInvalidSelection(String kind) throws Exception {
        String content = switch (kind) {
            case "whitespace" -> " {\"mode\":\"full\",\"schemaVersion\":1}";
            case "key-order" -> "{\"schemaVersion\":1,\"mode\":\"full\"}";
            case "unsorted" -> "{\"families\":[\"example.BetaIT\",\"example.AlphaIT\"],\"mode\":\"explicit\",\"schemaVersion\":1}";
            case "duplicate" -> "{\"families\":[\"example.AlphaIT\",\"example.AlphaIT\"],\"mode\":\"explicit\",\"schemaVersion\":1}";
            case "foreign" -> "{\"families\":[\"example.OtherIT\"],\"mode\":\"explicit\",\"schemaVersion\":1}";
            case "nested" -> "{\"families\":[\"example.AlphaIT$Nested\"],\"mode\":\"explicit\",\"schemaVersion\":1}";
            default -> "{\"families\":[],\"mode\":\"explicit\",\"schemaVersion\":1}";
        };
        Files.writeString(inputs.selection(), content);
        String mode = kind.equals("whitespace") || kind.equals("key-order") ? "full" : "explicit";
        var expected = json(inputs.expectedRequest());
        section(expected, "selection").put("mode", mode); section(expected, "selection").put("sha256", sha(inputs.selection()));
        save(inputs.expectedRequest(), expected);
        for (int index : List.of(1, 2)) {
            var root = node(index); section(root, "selection").putAll(section(expected, "selection")); saveNode(index, root);
        }
        rejected("SELECTION_MISMATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"baseline", "selection", "plan"})
    void semanticallyHarmlessByteChangesStillInvalidateTrustedDigests(String kind) throws Exception {
        Path file = switch (kind) {
            case "baseline" -> inputs.eligibility();
            case "selection" -> inputs.selection();
            default -> nodes.resolve("node-1/plan.yaml");
        };
        Files.writeString(file, Files.readString(file) + "\n");
        rejected(kind.equals("selection") ? "SELECTION_MISMATCH" : "ARTIFACT_HASH_MISMATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"junit-platform", "testng"})
    void legitimateRepeatedSpockIterationsAndTestNgRowsRequireARetainedNativeLedger(String runner) throws Exception {
        repeated(); rejected("AMBIGUOUS_EXECUTION_IDENTITY");
        ledger(runner, List.of(invocation("1", "example.AlphaIT", "passed"), invocation("2", "example.AlphaIT", "passed")));
        assertTrue(evaluate().passed(), evaluate().json());
    }

    @Test
    void nativeInvocationIdsMustBeUniqueAcrossNodesEvenWhenEveryNodesLocalCountsMatch() throws Exception {
        ledger(1, "junit-platform", List.of(invocation("1", "example.AlphaIT", "passed")));
        ledger(2, "junit-platform", List.of(invocation("1", "example.BetaIT", "passed")));
        var report = object(EvidenceJson.parse(rejected("AMBIGUOUS_EXECUTION_IDENTITY").json()));
        assertEquals("PASSED", section(report, "gates").get("provenance"));
        assertEquals("FAILED", section(report, "gates").get("reportedCoverage"));
        assertNull(report.get("totals"));
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "wrong-owner", "wrong-outcome", "missing-row", "tampered"})
    void aLedgerMustHaveUniqueNativeIdsAndAgreeWithEveryXmlOwnerAndOutcome(String kind) throws Exception {
        repeated();
        var rows = new ArrayList<>(List.of(invocation("1", "example.AlphaIT", "passed"), invocation("2", "example.AlphaIT", "passed")));
        switch (kind) {
            case "duplicate" -> rows.set(1, rows.get(0));
            case "wrong-owner" -> rows.set(1, invocation("2", "example.OtherIT", "passed"));
            case "wrong-outcome" -> rows.set(1, invocation("2", "example.AlphaIT", "skipped"));
            case "missing-row" -> rows.remove(1);
            default -> { }
        }
        ledger("junit-platform", rows);
        if (kind.equals("tampered")) Files.writeString(nodes.resolve("node-1/invocations.json"), "{}");
        assertFalse(evaluate().passed());
    }

    @ParameterizedTest @ValueSource(strings = {"flakyFailure", "flakyError", "rerunFailure", "rerunError"})
    void retriesAreNotSilentlySummedAsSuccessfulInvocations(String marker) throws Exception {
        report(1, suite("example.AlphaIT", "example.AlphaIT", "one", "<" + marker + "/>")); rejected("AMBIGUOUS_EXECUTION_IDENTITY");
    }

    @Test
    void overlappingReportsCannotDoubleCountAnInclusiveNestedSuite() throws Exception {
        var root = node(1);
        Path extra = nodes.resolve("node-1/reports/TEST-extra.xml"); Files.copy(xmlFile(1), extra);
        list(root, "reports").add(descriptor("reports/TEST-extra.xml", extra)); saveNode(1, root); rejected("AMBIGUOUS_EXECUTION_IDENTITY");
    }

    @ParameterizedTest @ValueSource(strings = {"reports", "cases", "bytes"})
    void splittingValidRowsAcrossFilesCannotEvadeNodeLimits(String boundary) throws Exception {
        Path second = xmlFile(1).resolveSibling("TEST-nested.xml");
        Files.writeString(second, suite("example.AlphaIT$Nested", "example.AlphaIT$Nested", "different", ""));
        var root = node(1); list(root, "reports").add(descriptor("reports/TEST-nested.xml", second)); root.put("observed", null); saveNode(1, root);
        assertTrue(evaluate().passed(), evaluate().json());
        var d = inputs.limits();
        int bytes = Math.toIntExact(Files.size(xmlFile(1)) + Files.size(second) - 1);
        var limits = new ExecutionEvidence.Limits(d.jsonBytes(), d.manifestBytes(), d.maxNodes(), boundary.equals("reports") ? 1 : d.maxReports(),
                d.maxReportBytes(), boundary.equals("bytes") ? bytes : d.maxTotalReportBytes(), d.maxDepth(), boundary.equals("cases") ? 1 : d.maxTestCases());
        inputs = new ExecutionEvidence.Inputs(inputs.expectedRequest(), inputs.eligibility(), inputs.selection(), nodes, limits);
        rejected("RESOURCE_LIMIT");
    }

    @Test
    void payloadMarkupCannotForgeExecutionsOrFailuresAndAbsentClassnameUsesTheSuiteOwner() throws Exception {
        report(1, "<testsuite name=\"example.AlphaIT\" tests=\"1\">"
                + "<properties><testcase classname=\"example.ForeignIT\" name=\"PRIVATE_CASE\"><failure/></testcase></properties>"
                + "<testcase name=\"real\"><system-out><failure>PRIVATE_OUTPUT</failure></system-out></testcase>"
                + "<system-err><testcase classname=\"example.ForeignIT\" name=\"PRIVATE_OTHER\"/></system-err></testsuite>");
        var result = evaluate(); assertTrue(result.passed(), result.json());
        assertFalse((result.json() + result.html()).contains("PRIVATE_"));
        assertEquals(new BigDecimal("2"), section(object(EvidenceJson.parse(result.json())), "totals").get("tests"));
    }

    @Test
    void anExplicitSkipPolicyCanAllowAnAllSkippedCohortWithoutClaimingExecution() throws Exception {
        report(1, suite("example.AlphaIT", "example.AlphaIT", "one", "<skipped/>"));
        report(2, suite("example.BetaIT", "example.BetaIT", "two", "<skipped/>"));
        var expected = json(inputs.expectedRequest()); expected.put("skipPolicy", "allow"); expected.put("maximumSkippedTests", null);
        expected.put("minimumExecutedTests", 0); save(inputs.expectedRequest(), expected);
        var result = evaluate(); assertTrue(result.passed(), result.json());
        assertEquals(new BigDecimal("0"), section(object(EvidenceJson.parse(result.json())), "totals").get("executed"));
        assertTrue(result.html().contains("executed: 0")); assertTrue(result.html().contains("skipped: 2"));
    }

    @ParameterizedTest @ValueSource(strings = {"truncated", "entity", "negative", "nan", "counter", "namespace", "retry-summary", "parent-counter", "not-run"})
    void malformedAndUnsupportedXmlNeverLeaksPayloadsOrCreatesPartialCounts(String kind) throws Exception {
        String content = switch (kind) {
            case "truncated" -> "<testsuite name=\"PRIVATE_DATA\"";
            case "entity" -> "<!DOCTYPE testsuite [<!ENTITY private SYSTEM 'file:///PRIVATE_SECRET'>]>" + suite("example.AlphaIT", "example.AlphaIT", "one", "&private;");
            case "negative" -> suite("example.AlphaIT", "example.AlphaIT", "one", "").replace("tests=\"1\"", "tests=\"-1\"");
            case "nan" -> suite("example.AlphaIT", "example.AlphaIT", "one", "").replace("time=\"1\"", "time=\"NaN\"");
            case "counter" -> suite("example.AlphaIT", "example.AlphaIT", "one", "").replace("tests=\"1\"", "tests=\"2\"");
            case "namespace" -> suite("example.AlphaIT", "example.AlphaIT", "one", "").replace("<testsuite ", "<testsuite xmlns=\"PRIVATE_NAMESPACE\" ");
            case "parent-counter" -> "<testsuites tests=\"2\">" + suite("example.AlphaIT", "example.AlphaIT", "one", "") + "</testsuites>";
            case "not-run" -> suite("example.AlphaIT", "example.AlphaIT", "one", "").replace("<testcase ", "<testcase status=\"notrun\" ");
            default -> "<testsuites><testsuite name=\"example.AlphaIT\" tests=\"1\"><testsuite name=\"example.AlphaIT$Nested\" tests=\"1\"/></testsuite></testsuites>";
        };
        report(1, content);
        var result = evaluate(); assertFalse(result.passed()); assertTrue(result.json().contains("\"totals\":null"));
        assertFalse((result.json() + result.html()).contains("PRIVATE_")); assertFalse(result.json().contains(directory.toString()));
    }

    @ParameterizedTest @ValueSource(strings = {"../TEST-out.xml", "/TEST-out.xml", "reports/../TEST-a.xml", "reports\\TEST-a.xml", "C:/TEST-a.xml"})
    void pathsMustBeNormalizedRelativePosixNamesBeforeAnyFileRead(String name) throws Exception {
        var root = node(1); object(list(root, "reports").get(0)).put("path", name); saveNode(1, root); rejected("UNSAFE_ARTIFACT_PATH");
    }

    @ParameterizedTest @ValueSource(strings = {"bytes", "hash", "extra-report", "unknown-xml", "observed"})
    void checksExactReportBytesUndeclaredFilesAndUntrustedCounters(String kind) throws Exception {
        var root = node(1);
        switch (kind) {
            case "bytes" -> Files.writeString(xmlFile(1), Files.readString(xmlFile(1)) + " ");
            case "hash" -> object(list(root, "reports").get(0)).put("sha256", "0".repeat(64));
            case "extra-report" -> Files.writeString(xmlFile(1).resolveSibling("TEST-extra.xml"), "<testsuite/>");
            case "unknown-xml" -> Files.writeString(xmlFile(1).resolveSibling("unlisted.xml"), "<PRIVATE_UNKNOWN_DIALECT/>");
            default -> section(root, "observed").put("tests", 10);
        }
        saveNode(1, root); assertFalse(evaluate().passed());
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "duplicate", "yaml", "invalid-utf8", "surrogate"})
    void strictJsonRejectsUnknownFieldsDuplicateKeysYamlAndMalformedUnicode(String kind) throws Exception {
        Path file = nodes.resolve("node-1/evidence.json"); String content = Files.readString(file);
        switch (kind) {
            case "unknown" -> content = content.replaceFirst("\\{", "{\"PRIVATE_KEY\":true,");
            case "duplicate" -> content = content.replaceFirst("\\{", "{\"observed\":null,");
            case "yaml" -> content = "{schemaVersion: 1}";
            case "surrogate" -> content = content.replace("example-job-1", "\\ud800");
            default -> { Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28}); rejected("INVALID_EVIDENCE"); return; }
        }
        Files.writeString(file, content); rejected("INVALID_EVIDENCE");
    }

    @Test
    void hardLinkedPayloadAliasesAreRejected() throws Exception {
        Files.createLink(xmlFile(1).resolveSibling("TEST-alias.xml"), xmlFile(1)); rejected("UNSAFE_ARTIFACT_PATH");
    }

    @ParameterizedTest @ValueSource(strings = {"xml", "plan", "node"})
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows symlinks require a privilege absent from ordinary developer accounts.
    void symbolicFilesAndNodeDirectoriesAreRejectedBeforeTheirTargetsAreRead(String kind) throws Exception {
        Path original = kind.equals("node") ? nodes.resolve("node-1") : kind.equals("plan") ? nodes.resolve("node-1/plan.yaml") : xmlFile(1);
        Path target = directory.resolve("external-target");
        Files.move(original, target);
        Files.createSymbolicLink(original, target);
        rejected("UNSAFE_ARTIFACT_PATH");
    }

    @ParameterizedTest @ValueSource(strings = {"json", "manifest", "xml", "total", "depth", "cases", "nodes"})
    void loweredResourceLimitsAreEnforcedAndRecorded(String kind) {
        var d = ExecutionEvidence.Limits.defaults();
        var limits = new ExecutionEvidence.Limits(kind.equals("json") ? 64 : d.jsonBytes(), kind.equals("manifest") ? 16 : d.manifestBytes(),
                kind.equals("nodes") ? 1 : d.maxNodes(), d.maxReports(), kind.equals("xml") ? 64 : d.maxReportBytes(),
                kind.equals("total") ? 64 : d.maxTotalReportBytes(), kind.equals("depth") ? 1 : d.maxDepth(), kind.equals("cases") ? 1 : d.maxTestCases());
        if (kind.equals("cases")) assertDoesNotThrow(this::repeated);
        inputs = new ExecutionEvidence.Inputs(inputs.expectedRequest(), inputs.eligibility(), inputs.selection(), nodes, limits);
        rejected("RESOURCE_LIMIT");
    }

    @Test
    void malformedExpectedRequestStillReplacesPreviousSuccessWithPayloadFreeFailureReports() throws Exception {
        Path json = directory.resolve("reports/result.json"), html = directory.resolve("reports/result.html");
        assertTrue(ExecutionEvidence.writeReports(inputs, json, html).passed());
        Files.writeString(inputs.expectedRequest(), "PRIVATE_INVALID_REQUEST");
        assertFalse(ExecutionEvidence.writeReports(inputs, json, html).passed());
        assertTrue(Files.readString(json).contains("\"cohort\":null"));
        assertFalse(Files.readString(html).contains("PRIVATE_"));
    }

    @Test
    void outputsCannotOverwriteInputsEvenThroughHardLinks() throws Exception {
        byte[] before = Files.readAllBytes(inputs.expectedRequest());
        assertThrows(IllegalArgumentException.class, () -> ExecutionEvidence.writeReports(inputs, inputs.expectedRequest(), directory.resolve("out.html")));
        Path alias = directory.resolve("alias.json"); Files.createLink(alias, xmlFile(1));
        assertThrows(IllegalArgumentException.class, () -> ExecutionEvidence.writeReports(inputs, alias, directory.resolve("out.html")));
        assertArrayEquals(before, Files.readAllBytes(inputs.expectedRequest()));
        assertFalse(Files.exists(directory.resolve("out.html")));
    }

    @Test
    void directoryPreparationFailureCannotLeaveAnEarlierSuccessfulJson() throws Exception {
        Path json = directory.resolve("reports/result.json");
        assertTrue(ExecutionEvidence.writeReports(inputs, json, directory.resolve("reports/result.html")).passed());
        Path blocker = directory.resolve("blocked-parent"); Files.writeString(blocker, "not a directory");
        assertThrows(IOException.class, () -> ExecutionEvidence.writeReports(inputs, json, blocker.resolve("result.html")));
        assertFalse(Files.exists(json), "an output I/O error must not leave the earlier PASSED JSON");
        assertEquals("not a directory", Files.readString(blocker));
    }

    @ParameterizedTest @ValueSource(strings = {"same", "ancestor", "inside-node"})
    void invalidOutputOwnershipIsRejectedBeforeAnyReportOrInputIsChanged(String kind) throws Exception {
        Path json = directory.resolve("reports/result.json"); Files.createDirectories(json.getParent()); Files.writeString(json, "previous output");
        Path first = kind.equals("inside-node") ? nodes.resolve("node-1/new/result.json") : json;
        Path second = kind.equals("same") ? first : kind.equals("ancestor") ? first.resolve("child.html") : directory.resolve("result.html");
        byte[] request = Files.readAllBytes(inputs.expectedRequest());
        assertThrows(IllegalArgumentException.class, () -> ExecutionEvidence.writeReports(inputs, first, second));
        assertEquals("previous output", Files.readString(json));
        assertArrayEquals(request, Files.readAllBytes(inputs.expectedRequest()));
        assertFalse(Files.exists(nodes.resolve("node-1/new")));
    }

    private void repeated() throws Exception {
        report(1, "<testsuite name=\"example.AlphaIT\" tests=\"2\" time=\"1\"><testcase classname=\"example.AlphaIT\" name=\"same\"/><testcase classname=\"example.AlphaIT\" name=\"same\"/></testsuite>");
        var root = node(1); root.put("observed", Map.of("tests", 2, "failures", 0, "errors", 0, "skipped", 0)); saveNode(1, root);
    }

    private void ledger(String runner, List<Map<String, Object>> rows) throws Exception {
        ledger(1, runner, rows);
    }

    private void ledger(int index, String runner, List<Map<String, Object>> rows) throws Exception {
        Path file = nodes.resolve("node-" + index + "/invocations.json"); save(file, Map.of("schemaVersion", 1, "runner", runner, "invocations", rows));
        var root = node(index); root.put("invocations", descriptor("invocations.json", file)); saveNode(index, root);
    }
    private static Map<String, Object> invocation(String id, String owner, String outcome) { return Map.of("id", id.repeat(64), "className", owner, "outcome", outcome); }
    private static Map<String, Object> descriptor(String relative, Path file) throws IOException { return Map.of("path", relative, "bytes", Files.size(file), "sha256", sha(file)); }
    /** Rebind exact report bytes so parser tests reach XML semantics instead of failing its hash first. */
    private void report(int index, String content) throws Exception {
        Path file = xmlFile(index); Files.writeString(file, content);
        var root = node(index); root.put("reports", new ArrayList<>(List.of(descriptor("reports/" + file.getFileName(), file))));
        root.put("observed", null); saveNode(index, root);
    }
    /** Changes both trusted and producer bindings to isolate partition semantics from tamper checks. */
    private void bindSecondNodePlan(Set<String> names) throws Exception {
        Path file = nodes.resolve("node-2/plan.yaml"); ClassManifest.write(file, names);
        var expected = json(inputs.expectedRequest()); object(list(expected, "expectedNodes").get(1)).put("planSha256", sha(file)); save(inputs.expectedRequest(), expected);
        var root = node(2); section(root, "selection").put("planSha256", sha(file)); saveNode(2, root);
    }
    private static String suite(String suite, String owner, String name, String outcome) {
        return "<testsuite name=\"" + suite + "\" tests=\"1\" failures=\"" + (outcome.startsWith("<failure>") ? 1 : 0)
                + "\" errors=\"" + (outcome.startsWith("<error>") ? 1 : 0) + "\" skipped=\"" + (outcome.startsWith("<skipped") ? 1 : 0)
                + "\" time=\"1\"><testcase classname=\"" + owner + "\" name=\"" + name + "\">" + outcome + "</testcase></testsuite>";
    }
    private void removeNode(int index) throws IOException {
        try (var paths = Files.walk(nodes.resolve("node-" + index))) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private Path xmlFile(int index) { return nodes.resolve("node-" + index + "/reports/TEST-example." + (index == 1 ? "AlphaIT" : "BetaIT") + ".xml"); }
    private Map<String, Object> node(int index) throws IOException { return json(nodes.resolve("node-" + index + "/evidence.json")); }
    private void saveNode(int index, Object value) throws IOException { save(nodes.resolve("node-" + index + "/evidence.json"), value); }
    private static void save(Path file, Object value) throws IOException { Files.writeString(file, EvidenceJson.write(value)); }
    private static String sha(Path file) throws IOException { return EvidenceFiles.digest(Files.readAllBytes(file)); }
    private ExecutionEvidence.Evaluation evaluate() { return ExecutionEvidence.evaluate(inputs); }
    private ExecutionEvidence.Evaluation rejected(String reason) { var result = evaluate(); assertFalse(result.passed(), result.json()); assertTrue(result.json().contains("\"" + reason + "\""), result.json()); return result; }
    private static @NonNull Map<String, Object> json(Path file) throws IOException { return object(EvidenceJson.parse(Files.readString(file))); }
    @SuppressWarnings("unchecked") // JSON object keys are strings; retain the original mutable fixture map.
    private static @NonNull Map<String, Object> object(@Nullable Object value) {
        return (Map<String, Object>) assertInstanceOf(Map.class, value);
    }
    private static @NonNull Map<String, Object> section(Map<String, Object> value, String key) { return object(value.get(key)); }
    @SuppressWarnings("unchecked") // Element shapes are checked by object() at each fixture access.
    private static @NonNull List<Object> list(Map<String, Object> value, String key) {
        return (List<Object>) assertInstanceOf(List.class, value.get(key));
    }
    private static Path examples() {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("docs/examples/execution-evidence-testdata"))) root = root.getParent();
        return root.resolve("docs/examples/execution-evidence-testdata");
    }
}
