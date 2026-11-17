package io.github.sjacek.shardpilot.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The Maven gate delegates to the same core without a project, session or native runner resolution. */
class VerifyExecutionsMojoTest {
    @TempDir Path directory;
    private VerifyExecutionsMojo goal;

    @BeforeEach
    void fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("docs/examples/execution-evidence-testdata"))) root = root.getParent();
        Path example = root.resolve("docs/examples/execution-evidence-testdata");
        Files.createDirectories(directory.resolve("trusted"));
        for (String name : List.of("expected.json", "baseline.yaml", "selection.json")) Files.copy(example.resolve(name), directory.resolve("trusted/" + name));
        for (int index : List.of(1, 2)) {
            try (var files = Files.walk(example.resolve("node-" + index))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = directory.resolve("nodes/node-" + index).resolve(example.resolve("node-" + index).relativize(file));
                    Files.createDirectories(target.getParent()); Files.copy(file, target);
                }
            }
        }
        goal = new VerifyExecutionsMojo();
        set("expectedRequest", directory.resolve("trusted/expected.json").toFile());
        set("eligibilityFile", directory.resolve("trusted/baseline.yaml").toFile());
        set("selectionFile", directory.resolve("trusted/selection.json").toFile());
        set("nodesDirectory", directory.resolve("nodes").toFile());
        set("reportFile", directory.resolve("out/result.json").toFile()); set("htmlReportFile", directory.resolve("out/result.html").toFile());
    }

    @ParameterizedTest @ValueSource(strings = {"surefire", "failsafe"})
    void acceptsBothMavenRunnerCohortsAndRejectsAnAccidentalRunnerMix(String runner) throws Exception {
        maven(runner); goal.execute();
        assertTrue(json().contains("\"status\":\"PASSED\""));
        Path evidence = directory.resolve("nodes/node-2/evidence.json");
        Files.writeString(evidence, Files.readString(evidence).replace(runner, runner.equals("surefire") ? "failsafe" : "surefire"));
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("COHORT_MISMATCH"));
    }

    @Test
    void replacesPreviousSuccessAfterNativeFailureWithoutManufacturingMissingInputs() throws Exception {
        maven("surefire"); goal.execute();
        Path evidence = directory.resolve("nodes/node-1/evidence.json");
        Files.writeString(evidence, Files.readString(evidence).replace("\"exitCode\": 0", "\"exitCode\": 137"));
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("NATIVE_PROCESS_FAILED"));
        assertTrue(Files.readString(directory.resolve("out/result.html")).contains("execution evidence: FAILED"));
        assertFalse(Files.exists(directory.resolve("target/test-classes")));
    }

    @Test
    void missingExpectedRequestWritesUnknownCountsInsteadOfReusingAnOldSuccess() throws Exception {
        goal.execute(); Files.delete(directory.resolve("trusted/expected.json"));
        assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("\"totals\":null"));
    }

    @Test
    void loweredLimitsAreAppliedAndOutputAliasValidationPreservesInputs() throws Exception {
        set("jsonBytes", 32); assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("RESOURCE_LIMIT"));
        File original = directory.resolve("trusted/expected.json").toFile(); String before = Files.readString(original.toPath());
        set("reportFile", original); assertThrows(MojoFailureException.class, goal::execute);
        assertEquals(before, Files.readString(original.toPath()));
    }

    @Test
    void invalidLimitsFailBeforeCreatingReportsOrReadingAnInvalidRequest() throws Exception {
        set("maxNodes", 0);
        Files.writeString(directory.resolve("trusted/expected.json"), "PRIVATE_INVALID_REQUEST");
        var failure = assertThrows(MojoFailureException.class, goal::execute);
        assertEquals("Invalid ShardPilot execution-evidence configuration", failure.getMessage()); assertNull(failure.getCause());
        assertFalse(Files.exists(directory.resolve("out")));
    }

    @Test
    void unwritableOutputsAreExecutionErrorsWithNoPrivateFilesystemCause() throws Exception {
        Path blocker = directory.resolve("PRIVATE_PARENT"); Files.writeString(blocker, "not a directory");
        set("reportFile", blocker.resolve("result.json").toFile());
        var failure = assertThrows(MojoExecutionException.class, goal::execute);
        assertEquals("Cannot write ShardPilot execution-evidence reports", failure.getMessage()); assertNull(failure.getCause());
        assertFalse(Files.exists(directory.resolve("out/result.html")));
        assertEquals("not a directory", Files.readString(blocker));
    }

    @Test
    void aSuccessfulRunCanRecoverFromMissingEvidenceAndReplacesBothFailedReports() throws Exception {
        Path expected = directory.resolve("trusted/expected.json"); byte[] retained = Files.readAllBytes(expected);
        Files.delete(expected); assertThrows(MojoFailureException.class, goal::execute);
        assertTrue(json().contains("\"status\":\"FAILED\""));
        Files.write(expected, retained); goal.execute();
        assertTrue(json().contains("\"status\":\"PASSED\""));
        assertTrue(Files.readString(directory.resolve("out/result.html")).contains("execution evidence: PASSED"));
        assertFalse(Files.exists(directory.resolve("target")));
    }

    /** Changes trusted and producer identities together; the XML/plan byte bindings remain exact. */
    private void maven(String runner) throws Exception {
        for (Path path : List.of(directory.resolve("trusted/expected.json"), directory.resolve("nodes/node-1/evidence.json"), directory.resolve("nodes/node-2/evidence.json"))) {
            Files.writeString(path, Files.readString(path).replace("\"gradle\"", "\"maven\"").replace("gradle-test", runner));
        }
    }
    private void set(String name, Object value) throws Exception { var field = VerifyExecutionsMojo.class.getDeclaredField(name); field.setAccessible(true); field.set(goal, value); }
    private String json() throws Exception { return Files.readString(directory.resolve("out/result.json")); }
}
