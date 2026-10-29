package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.DurationHistoryRefresh;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Artifact contracts and configuration edge cases complement real Surefire/Failsafe Invoker runs. */
class RefreshDurationsMojoTest {
    @TempDir Path directory;
    private RefreshDurationsMojo mojo;
    private Path reports;
    private Path output;
    private Path report;
    private Path baseline;

    @BeforeEach
    void fixture() throws IOException {
        mojo = context(directory);
        reports = directory.resolve("target/surefire-reports");
        output = mojo.namespace().resolve("durations.tsv");
        report = mojo.namespace().resolve("durations.json");
        baseline = mojo.planDirectory().resolve("baseline.yaml");
        ClassManifest.write(baseline, Set.of("example.AlphaTest", "example.BetaTest"));
        mojo.durationsFile = directory.resolve("history.tsv").toFile();
        Files.writeString(mojo.durationsFile.toPath(), "example.AlphaTest$Nested\t9\nexample.BetaTest\t4\nexample.OldTest\t7\n");
        xml(reports, "AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"0\" time=\"2.26\">"
                + "<testcase classname=\"example.AlphaTest\" name=\"outer\"/>"
                + "<testcase classname=\"example.AlphaTest$Nested\" name=\"nested\"/></testsuite>");
    }

    @Test
    void defaultsNormalizeInclusiveFamilyKeepUnmeasuredAndPruneForeignWithoutTouchingInputs() throws Exception {
        String previous = Files.readString(mojo.durationsFile.toPath());
        String manifest = Files.readString(baseline);
        mojo.shardCount = 2;
        mojo.execute();
        assertEquals(Map.of("example.AlphaTest", 2.3, "example.BetaTest", 4.0), candidate());
        String json = Files.readString(report);
        for (String field : List.of("\"status\":\"PASSED\"", "\"measured\":1", "\"replaced\":1",
                "\"kept\":1", "\"dropped\":1", "\"coverage\":0.5", "\"shardCount\":2", "\"pruning\":\"baseline\"")) {
            assertTrue(json.contains(field), field);
        }
        assertEquals(previous, Files.readString(mojo.durationsFile.toPath()));
        assertEquals(manifest, Files.readString(baseline));
        String first = Files.readString(output);
        mojo.execute();
        assertEquals(first, Files.readString(output), "Refreshing the same evidence is deterministic");
        assertFalse(Files.exists(directory.resolve("target/test-classes")), "Artifact refresh must not compile");
    }

    @Test
    void customSuiteAndIndependentPathsDoNotRequireNativeRunnerConfiguration() throws Exception {
        mojo.suite = "privacy";
        mojo.directory = directory.resolve("custom evidence").toFile();
        mojo.plannedDirectory = baseline.getParent().toFile();
        set("outputFile", directory.resolve("custom output/candidate.tsv").toFile());
        set("reportFile", directory.resolve("custom report/result.json").toFile());
        mojo.execute();
        assertTrue(Files.exists(directory.resolve("custom output/candidate.tsv")));
        assertTrue(Files.readString(directory.resolve("custom report/result.json")).contains("\"status\":\"PASSED\""));
        assertFalse(Files.exists(mojo.namespace()));
    }

    @ParameterizedTest
    @EnumSource(TestRunner.class)
    void defaultReportAndSuitePathsFollowRunnerWithoutReadingTheOtherRunner(TestRunner runner) throws Exception {
        mojo.runner = runner;
        ClassManifest.write(mojo.planDirectory().resolve("baseline.yaml"), Set.of("example.AlphaTest"));
        if (runner == TestRunner.FAILSAFE) {
            xml(directory.resolve("target/failsafe-reports"), "AlphaTest",
                    "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"8\"/>");
        }
        mojo.execute();
        var history = DurationHistory.parse(Files.readString(mojo.namespace().resolve("durations.tsv")));
        assertEquals(runner == TestRunner.SUREFIRE ? 2.3 : 8.0, history.get("example.AlphaTest"));
    }

    @Test
    void explicitMultipleReportRootsOverrideNativeDefaults() throws Exception {
        Path one = directory.resolve("collected shard one");
        Path two = directory.resolve("collected shard two");
        xml(one, "AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"3\"/>");
        xml(two, "BetaTest", "<testsuite name=\"example.BetaTest\" tests=\"1\" time=\"5\"/>");
        set("reports", new File[] {one.toFile(), two.toFile()});
        mojo.execute();
        assertEquals(Map.of("example.AlphaTest", 3.0, "example.BetaTest", 5.0), candidate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-baseline", "invalid-baseline", "empty-baseline", "missing-reports",
            "invalid-history", "unreadable-history", "malformed-xml", "xxe", "duplicate", "coverage", "failed", "retry"})
    void dataFailuresWriteJsonRemoveStaleTsvAndPreserveMaintainedHistory(String kind) throws Exception {
        String reason = switch (kind) {
            case "missing-baseline" -> { Files.delete(baseline); yield "INVALID_BASELINE"; }
            case "invalid-baseline" -> { Files.writeString(baseline, "classes: invalid"); yield "INVALID_BASELINE"; }
            case "empty-baseline" -> { ClassManifest.write(baseline, Set.of()); yield "EMPTY_ELIGIBILITY"; }
            case "missing-reports" -> { Files.delete(reports.resolve("TEST-AlphaTest.xml")); yield "NO_MEASUREMENTS"; }
            case "invalid-history" -> { Files.writeString(mojo.durationsFile.toPath(), "sensitive-invalid-metadata"); yield "INVALID_HISTORY"; }
            case "unreadable-history" -> { mojo.durationsFile = directory.toFile(); yield "INVALID_HISTORY"; }
            case "malformed-xml" -> { xml(reports, "ZBroken", "<testsuite><secret-payload"); yield "INVALID_XML"; }
            case "xxe" -> { xml(reports, "ZBroken", "<!DOCTYPE testsuite SYSTEM 'file:///secret-payload'><testsuite/>"); yield "INVALID_XML"; }
            case "duplicate" -> { xml(reports, "Copy", Files.readString(reports.resolve("TEST-AlphaTest.xml"))); yield "DUPLICATE_SUITE"; }
            case "coverage" -> { set("minimumCoverage", 1.0); yield "MINIMUM_COVERAGE"; }
            case "failed" -> { xml(reports, "AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"1\" failures=\"1\"/>"); yield "NO_MEASUREMENTS"; }
            case "retry" -> { xml(reports, "AlphaTest", "<testsuite name=\"example.AlphaTest\" tests=\"1\" time=\"1\"><testcase name=\"x\"><flakyFailure>secret-payload</flakyFailure></testcase></testsuite>"); yield "NO_MEASUREMENTS"; }
            default -> throw new AssertionError(kind);
        };
        Files.createDirectories(output.getParent());
        Files.writeString(output, "stale candidate");
        String previous = mojo.durationsFile.isFile() ? Files.readString(mojo.durationsFile.toPath()) : null;
        var failure = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(failure.getMessage().contains(reason));
        assertFalse(Files.exists(output));
        String json = Files.readString(report);
        assertTrue(json.contains("\"status\":\"FAILED\""));
        assertTrue(json.contains("\"reason\":\"" + reason + "\""));
        assertFalse(json.contains("secret-payload"));
        assertFalse(json.contains("sensitive-invalid-metadata"));
        if (previous != null) assertEquals(previous, Files.readString(mojo.durationsFile.toPath()));
        if (kind.equals("malformed-xml")) {
            assertTrue(json.contains("\"measured\":1"));
            assertTrue(json.contains("\"refreshed\":null"));
        }
    }

    @Test
    void explicitSumAndIncludeFailuresAreAppliedByTheSharedCore() throws Exception {
        xml(reports, "Copy", "<testsuite name=\"example.AlphaTest\" tests=\"1\" failures=\"1\" time=\"1.04\"/>");
        set("duplicates", DurationHistoryRefresh.Duplicates.SUM);
        set("includeFailedSuites", true);
        mojo.execute();
        assertEquals(3.3, candidate().get("example.AlphaTest"));
        assertTrue(Files.readString(report).contains("\"measuredSuites\":1"));
    }

    @ParameterizedTest
    @CsvSource({"AUTO,baseline", "BASELINE,baseline", "CLASS_DIRECTORIES,classDirectories", "DISABLED,disabled"})
    void allPruningModesResolveTheirMavenConventions(HistoryPruning pruning, String label) throws Exception {
        set("pruning", pruning);
        if (pruning == HistoryPruning.CLASS_DIRECTORIES) classes(Path.of(mojo.project.getBuild().getTestOutputDirectory()));
        mojo.execute();
        assertTrue(Files.readString(report).contains("\"pruning\":\"" + label + "\""));
        assertEquals(pruning == HistoryPruning.DISABLED, candidate().containsKey("example.OldTest"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline", "classes"})
    void explicitEligibilityOverridesSuiteBaseline(String source) throws Exception {
        Files.delete(baseline);
        if (source.equals("baseline")) {
            Path explicit = directory.resolve("another baseline.yaml");
            ClassManifest.write(explicit, Set.of("example.AlphaTest"));
            set("baselineFile", explicit.toFile());
        } else {
            Path roots = directory.resolve("supplied classes");
            classes(roots);
            set("classDirectories", new File[] {roots.toFile()});
        }
        mojo.execute();
        assertEquals(Set.of("example.AlphaTest"), candidate().keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto-both", "baseline-classes", "classes-baseline", "disabled-baseline", "disabled-classes",
            "disabled-coverage", "precision", "zero-floor", "rounding-floor", "nan-floor", "coverage", "shard-count", "suite"})
    void invalidConfigurationPreservesExistingArtifactsWithoutPublishingFailureJson(String kind) throws Exception {
        switch (kind) {
            case "auto-both" -> { set("baselineFile", baseline.toFile()); set("classDirectories", new File[] {directory.toFile()}); }
            case "baseline-classes" -> { set("pruning", HistoryPruning.BASELINE); set("classDirectories", new File[] {directory.toFile()}); }
            case "classes-baseline" -> { set("pruning", HistoryPruning.CLASS_DIRECTORIES); set("baselineFile", baseline.toFile()); }
            case "disabled-baseline" -> { set("pruning", HistoryPruning.DISABLED); set("baselineFile", baseline.toFile()); }
            case "disabled-classes" -> { set("pruning", HistoryPruning.DISABLED); set("classDirectories", new File[] {directory.toFile()}); }
            case "disabled-coverage" -> { set("pruning", HistoryPruning.DISABLED); set("minimumCoverage", .5); }
            case "precision" -> set("precision", 7);
            case "zero-floor" -> set("minimumSeconds", 0.0);
            case "rounding-floor" -> set("precision", 0);
            case "nan-floor" -> set("minimumSeconds", Double.NaN);
            case "coverage" -> set("minimumCoverage", 1.1);
            case "shard-count" -> mojo.shardCount = 0;
            case "suite" -> mojo.suite = "../escape";
            default -> throw new AssertionError(kind);
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, "previous TSV");
        Files.writeString(report, "previous JSON");
        assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("previous TSV", Files.readString(output));
        assertEquals("previous JSON", Files.readString(report));
    }

    @ParameterizedTest
    @ValueSource(strings = {"history-output", "history-json", "baseline-output", "baseline-json", "same-output",
            "xml-output", "xml-json", "hardlink-history", "hardlink-xml", "normalized-history"})
    void outputAliasesAreRejectedBeforeAnyInputCanBeChanged(String kind) throws Exception {
        Path history = mojo.durationsFile.toPath();
        Path xml = reports.resolve("TEST-AlphaTest.xml");
        String historyBefore = Files.readString(history);
        String baselineBefore = Files.readString(baseline);
        String xmlBefore = Files.readString(xml);
        switch (kind) {
            case "history-output" -> set("outputFile", history.toFile());
            case "history-json" -> set("reportFile", history.toFile());
            case "baseline-output" -> set("outputFile", baseline.toFile());
            case "baseline-json" -> set("reportFile", baseline.toFile());
            case "same-output" -> set("reportFile", output.toFile());
            case "xml-output" -> set("outputFile", xml.toFile());
            case "xml-json" -> set("reportFile", xml.toFile());
            case "hardlink-history" -> { Files.createDirectories(output.getParent()); Files.createLink(output, history); }
            case "hardlink-xml" -> { Files.createDirectories(output.getParent()); Files.createLink(output, xml); }
            case "normalized-history" -> { Files.createDirectories(directory.resolve("sub")); set("outputFile", directory.resolve("sub/../history.tsv").toFile()); }
            default -> throw new AssertionError(kind);
        }
        assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals(historyBefore, Files.readString(history));
        assertEquals(baselineBefore, Files.readString(baseline));
        assertEquals(xmlBefore, Files.readString(xml));
    }

    @Test
    void missingHistoryIsAColdStartButMissingSelectedClassDirectoryIsADataFailure() throws Exception {
        Files.delete(mojo.durationsFile.toPath());
        mojo.execute();
        assertEquals(Map.of("example.AlphaTest", 2.3), candidate());
        set("pruning", HistoryPruning.CLASS_DIRECTORIES);
        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(output));
        assertTrue(Files.readString(report).contains("\"reason\":\"INVALID_BASELINE\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outputFile", "reportFile"})
    void artifactWriteFailuresAreExecutionErrors(String field) throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("child"), "not an artifact");
        set(field, blocked.toFile());
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    void pomOnlyAggregatorsSkipEvenInvalidRefreshConfiguration() throws Exception {
        mojo.project.setPackaging("pom");
        mojo.shardCount = 0;
        mojo.suite = "../invalid";
        mojo.execute();
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(report));
    }

    @Test
    void independentProjectsNeverSharePathsOrPublishSessionProperties() throws Exception {
        RefreshDurationsMojo other = context(directory.resolve("other-module"));
        Path otherReports = directory.resolve("other-module/target/surefire-reports");
        ClassManifest.write(other.planDirectory().resolve("baseline.yaml"), Set.of("example.OtherTest"));
        xml(otherReports, "OtherTest", "<testsuite name=\"example.OtherTest\" tests=\"1\" time=\"6\"/>");
        mojo.execute();
        other.execute();
        assertEquals(Map.of("example.OtherTest", 6.0), DurationHistory.parse(Files.readString(other.namespace().resolve("durations.tsv"))));
        assertFalse(candidate().containsKey("example.OtherTest"));
        assertTrue(mojo.project.getProperties().isEmpty());
        assertTrue(other.project.getProperties().isEmpty());
    }

    private Map<String, Double> candidate() throws IOException { return DurationHistory.parse(Files.readString(output)); }

    private void set(String name, Object value) throws ReflectiveOperationException {
        var field = RefreshDurationsMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static void xml(Path root, String name, String contents) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("TEST-" + name + ".xml"), contents);
    }

    private static void classes(Path root) throws IOException {
        Path file = root.resolve("example/AlphaTest.class");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]); // Names are derived without loading consumer bytecode.
    }

    private static RefreshDurationsMojo context(Path root) {
        var goal = new RefreshDurationsMojo();
        goal.project = new MavenProject();
        goal.project.setFile(root.resolve("pom.xml").toFile());
        goal.project.setPackaging("jar");
        var build = new Build();
        build.setDirectory(root.resolve("target").toString());
        build.setTestOutputDirectory(root.resolve("target/test-classes").toString());
        goal.project.getModel().setBuild(build);
        return goal;
    }
}
