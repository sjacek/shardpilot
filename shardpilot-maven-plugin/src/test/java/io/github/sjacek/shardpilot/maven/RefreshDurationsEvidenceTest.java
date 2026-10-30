package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.DurationHistory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-phase contracts: source selection, family exclusions, diagnostic privacy and filesystem failures. */
class RefreshDurationsEvidenceTest {
    @TempDir
    Path directory;
    private RefreshDurationsMojo goal;
    private Path reports;
    private Path baseline;
    private Path candidate;
    private Path report;

    @BeforeEach
    void fixture() throws IOException, ReflectiveOperationException {
        goal = new RefreshDurationsMojo();
        goal.project = new MavenProject();
        goal.project.setFile(directory.resolve("pom.xml").toFile());
        goal.project.setPackaging("jar");
        var build = new Build();
        build.setDirectory(directory.resolve("target").toString());
        build.setTestOutputDirectory(directory.resolve("target/test-classes").toString());
        goal.project.getModel().setBuild(build);
        reports = directory.resolve("reports");
        baseline = goal.planDirectory().resolve("baseline.yaml");
        candidate = goal.namespace().resolve("durations.tsv");
        report = goal.namespace().resolve("durations.json");
        set("reports", new File[] {reports.toFile()});
        goal.durationsFile = directory.resolve("history.tsv").toFile();
        Files.writeString(goal.durationsFile.toPath(), "example.AlphaTest$Nested\t9\nexample.BetaTest\t4\nexample.OldTest\t7\n");
        ClassManifest.write(baseline, Set.of("example.AlphaTest", "example.BetaTest"));
        xml("AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"2.26\"/>");
    }

    @ParameterizedTest
    @EnumSource(value = HistoryPruning.class, names = {"AUTO", "BASELINE"})
    void missingSelectedBaselineNeverFallsBackToAvailableBytecode(HistoryPruning pruning) throws Exception {
        set("pruning", pruning);
        compiled(directory.resolve("target/test-classes"));
        Files.delete(baseline);
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("\"reason\":\"INVALID_BASELINE\""));
        assertTrue(json().contains("\"eligible\":null"));
        assertFalse(Files.exists(candidate));
    }

    @Test
    void disabledPruningDoesNotReadMalformedDefaultManifestOrMissingClassRoots() throws Exception {
        Files.writeString(baseline, "not a manifest");
        set("pruning", HistoryPruning.DISABLED);
        goal.execute();
        assertEquals(Map.of("example.AlphaTest", 2.3, "example.BetaTest", 4.0, "example.OldTest", 7.0), history());
        assertTrue(json().contains("\"eligible\":null"));
        assertTrue(json().contains("\"coverage\":null"));
        assertFalse(Files.exists(directory.resolve("target/test-classes")));
    }

    @Test
    void overlappingReportPathsReadEachNormalizedPathOnce() throws Exception {
        set("reports", new File[] {reports.toFile(), reports.resolve("TEST-AlphaTest.xml").toFile(), reports.toFile()});
        goal.execute();
        assertEquals(2.3, history().get("example.AlphaTest"));
        assertTrue(json().contains("\"measuredSuites\":1,"));
    }

    @ParameterizedTest
    @EnumSource(value = HistoryPruning.class, names = {"AUTO", "CLASS_DIRECTORIES"})
    void explicitlyEmptyClassRootsAreAnEmptyEligibleSetRatherThanDisabledPruning(HistoryPruning pruning) throws Exception {
        Path empty = directory.resolve("empty classes");
        Files.createDirectories(empty);
        set("classDirectories", new File[] {empty.toFile()});
        set("pruning", pruning);
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("\"reason\":\"EMPTY_ELIGIBILITY\""));
        assertTrue(json().contains("\"eligible\":0,"));
        assertTrue(json().contains("\"foreign\":1,"));
    }

    @Test
    void duplicateBinaryNamesAcrossClassRootsFailAsInvalidEligibilityData() throws Exception {
        Path first = directory.resolve("first classes");
        Path second = directory.resolve("second classes");
        compiled(first);
        compiled(second);
        set("classDirectories", new File[] {first.toFile(), second.toFile()});
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("\"reason\":\"INVALID_BASELINE\""));
        assertFalse(Files.exists(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outputFile", "reportFile", "hardlink"})
    void classInputsAreProtectedFromBothOutputWritesAndFailureCleanup(String alias) throws Exception {
        Path root = directory.resolve("classes");
        Path bytecode = compiled(root);
        set("classDirectories", new File[] {root.toFile()});
        byte[] before = Files.readAllBytes(bytecode);
        if (alias.equals("hardlink")) {
            Files.createDirectories(candidate.getParent());
            Files.createLink(candidate, bytecode);
        } else set(alias, root.resolve("nested/new-output").toFile());
        assertThrows(MojoFailureException.class, goal::execute);
        assertArrayEquals(before, Files.readAllBytes(bytecode));
        assertFalse(Files.exists(root.resolve("nested")), "Alias validation must not create input directories");
        assertFalse(Files.exists(report));
    }

    @ParameterizedTest
    @ValueSource(strings = {"flakyFailure", "flakyError", "rerunFailure", "rerunError"})
    void includingFailuresNeverAdmitsRetriedFamilies(String marker) throws Exception {
        set("includeFailedSuites", true);
        xml("AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"100\">"
                + "<testcase name=\"retried\"><" + marker + ">sensitive-output</" + marker + "></testcase></testsuite>");
        xml("BetaTest", "<testsuite name=\"example.BetaTest\" tests=\"1\" time=\"3\"/>");
        goal.execute();
        assertEquals(Map.of("example.AlphaTest$Nested", 9.0, "example.BetaTest", 3.0), history());
        assertTrue(json().contains("\"retried\":1,"));
        assertFalse(json().contains("sensitive-output"));
    }

    @Test
    void excludedNestedMemberKeepsOldFamilyWhileAnIndependentFamilyIsRefreshed() throws Exception {
        xml("AlphaTest$Nested", "<testsuite name=\"example.AlphaTest$Nested\" tests=\"1\" failures=\"1\" time=\"6\"/>");
        xml("BetaTest", "<testsuite name=\"example.BetaTest\" tests=\"1\" time=\"3\"/>");
        goal.execute();
        assertEquals(Map.of("example.AlphaTest$Nested", 9.0, "example.BetaTest", 3.0), history());
        assertTrue(json().contains("\"ambiguous\":1,\"failed\":1"));
        assertTrue(json().contains("\"INCOMPLETE_FAMILY\":1"));
    }

    @Test
    void familyFloorIsAppliedOnceToDisjointSuiteTimesWithoutUsingTestcaseTimes() throws Exception {
        xml("AlphaTest$Nested", "<testsuite name=\"example.AlphaTest$Nested\" tests=\"1\" time=\"0.24\">"
                + "<testcase name=\"nested\" time=\"9999\"/></testsuite>");
        set("minimumSeconds", 3.0);
        goal.execute();
        assertEquals(3.0, history().get("example.AlphaTest"));
        assertTrue(json().contains("\"measured\":1,\"measuredSuites\":2,"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleLoggingAndJsonNeverContainTestPayloadsOrAbsoluteReportRoots(boolean malformed) throws Exception {
        var log = new RecordingLog();
        goal.setLog(log);
        xml("AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"2\">"
                + "<testcase name=\"PRIVATE_CASE_NAME\"/><system-out>PRIVATE_STDOUT</system-out>"
                + "<system-err>PRIVATE_STDERR</system-err><properties><property name=\"token\" value=\"PRIVATE_TOKEN\"/></properties></testsuite>");
        if (malformed) {
            xml("ZBroken", "<testsuite name=\"PRIVATE_XML_PAYLOAD\" time=\"not-a-number\"/>");
            assertThrows(MojoFailureException.class, goal::execute);
        } else goal.execute();
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0).startsWith("ShardPilot history " + (malformed ? "FAILED" : "PASSED")));
        String diagnostics = json() + log.lines;
        assertFalse(diagnostics.contains("PRIVATE_"));
        assertFalse(diagnostics.contains(directory.toString()));
        assertFalse(log.lines.get(0).contains("TEST-"));
    }

    @Test
    void successfulRecoveryReplacesFailedJsonAndRecreatesOnlyACompleteCandidate() throws Exception {
        xml("ZBroken", "<testsuite");
        assertThrows(MojoFailureException.class, goal::execute);
        assertFalse(Files.exists(candidate));
        Files.delete(reports.resolve("TEST-ZBroken.xml"));
        goal.execute();
        assertEquals(Map.of("example.AlphaTest", 2.3, "example.BetaTest", 4.0), history());
        assertTrue(json().contains("\"status\":\"PASSED\""));
        assertFalse(json().contains("\"reason\""));
    }

    @Test
    void staleCandidateRemovalFailureIsAnExecutionErrorAndPreservesPreviousJson() throws Exception {
        Files.delete(baseline);
        Files.createDirectories(candidate);
        Files.writeString(candidate.resolve("child"), "not a TSV");
        Files.writeString(report, "previous JSON");
        assertThrows(MojoExecutionException.class, goal::execute);
        assertEquals("previous JSON", json());
        assertTrue(Files.exists(candidate.resolve("child")));
    }

    @Test
    void failedDataWithUnwritableJsonStillRemovesTheStaleCandidate() throws Exception {
        Files.delete(baseline);
        Files.createDirectories(report);
        Files.writeString(report.resolve("child"), "not a JSON file");
        Files.writeString(candidate, "stale TSV");
        assertThrows(MojoExecutionException.class, goal::execute);
        assertFalse(Files.exists(candidate));
        assertTrue(Files.exists(report.resolve("child")));
    }

    @Test
    void invalidConfigurationCreatesNeitherFreshOutputDirectoriesNorFailureJson() throws Exception {
        Path fresh = directory.resolve("fresh outputs");
        set("outputFile", fresh.resolve("candidate.tsv").toFile());
        set("reportFile", fresh.resolve("evidence.json").toFile());
        set("precision", 0); // The default 0.1 floor would round to zero.
        assertThrows(MojoFailureException.class, goal::execute);
        assertFalse(Files.exists(fresh));
    }

    private Map<String, Double> history() throws IOException { return DurationHistory.parse(Files.readString(candidate)); }

    private String json() throws IOException { return Files.readString(report); }

    private void set(String name, Object value) throws ReflectiveOperationException {
        var field = RefreshDurationsMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(goal, value);
    }

    private void xml(String name, String contents) throws IOException {
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-" + name + ".xml"), contents);
    }

    private static Path compiled(Path root) throws IOException {
        Path bytecode = root.resolve("example/AlphaTest.class");
        Files.createDirectories(bytecode.getParent());
        Files.write(bytecode, new byte[] {1, 2, 3}); // Eligibility uses names without loading code.
        return bytecode;
    }

    /** Retains only the adapter's lifecycle channel without printing synthetic payloads to the console. */
    private static final class RecordingLog extends SystemStreamLog {
        private final List<String> lines = new ArrayList<>();
        @Override
        public void info(CharSequence content) { lines.add(content.toString()); }
    }
}
