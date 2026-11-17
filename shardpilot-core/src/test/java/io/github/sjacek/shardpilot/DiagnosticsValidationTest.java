package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.DiagnosticsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retained evidence is not a cached verdict: damage, foreign identities and unsafe output must fail. */
class DiagnosticsValidationTest {
    @TempDir Path directory;

    @Test void failureToLaunchACommandHasNoInventedExitCodeOrPrivateErrorPayload() throws Exception {
        var settings = settings(directory.resolve("session"));
        assertEquals(1, Diagnostics.run(settings, List.of(directory.resolve("PRIVATE_MISSING_EXECUTABLE").toString())));
        var result = Diagnostics.finish(settings);
        assertFalse(result.passed()); assertTrue(result.json().contains("PROCESS_START_FAILED"));
        Map<?, ?> process = assertInstanceOf(Map.class, object(result.json()).get("process"));
        assertEquals("start_failed", process.get("state")); assertNull(process.get("exitCode"));
        assertFalse(result.json().contains("PRIVATE")); assertFalse(result.html().contains(directory.toString()));
    }

    @ParameterizedTest @ValueSource(strings = {"started.json", "process.json", "samples.jsonl"})
    void missingEvidenceRevokesBothEarlierSuccessfulReports(String name) throws Exception {
        var settings = successful();
        Files.delete(settings.directory().resolve(name));
        var result = Diagnostics.finish(settings);
        assertFalse(result.passed());
        assertEquals(result.json(), Files.readString(settings.directory().resolve("report.json")));
        assertEquals(result.html(), Files.readString(settings.directory().resolve("report.html")));
        assertTrue(result.json().contains("\"status\":\"failed\""));
        assertTrue(result.html().contains("Execution diagnostics: failed"));
    }

    @ParameterizedTest @ValueSource(strings = {"unknown-state", "fractional-exit", "overflow-exit", "negative-elapsed", "remaining-descendants"})
    void invalidNativeResultsCannotRetainAnEarlierSuccess(String scenario) throws Exception {
        var settings = successful(); Path file = settings.directory().resolve("process.json");
        var process = read(file);
        switch (scenario) {
            case "unknown-state" -> process.put("state", "PRIVATE_INVALID_STATE");
            case "fractional-exit" -> process.put("exitCode", new java.math.BigDecimal("0.1"));
            case "overflow-exit" -> process.put("exitCode", Long.MAX_VALUE);
            case "negative-elapsed" -> process.put("elapsedMillis", -1);
            case "remaining-descendants" -> process.put("descendantsAlive", true);
            default -> fail("Unknown scenario");
        }
        Files.writeString(file, EvidenceJson.write(process));
        var result = Diagnostics.finish(settings);
        assertFalse(result.passed()); assertFalse(result.json().contains("PRIVATE_INVALID_STATE"));
        assertTrue(result.json().contains(scenario.equals("remaining-descendants") ? "DESCENDANTS_REMAIN" : "PROCESS_RESULT_MISSING_OR_INVALID"));
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "truncated", "invalid-utf8", "backwards-time", "negative-time"})
    void incompleteOrInconsistentSamplesKeepTheVerdictFailed(String scenario) throws Exception {
        var settings = successful(); Path file = settings.directory().resolve("samples.jsonl");
        var row = object(Files.readAllLines(file).get(0));
        switch (scenario) {
            case "empty" -> Files.writeString(file, "");
            case "truncated" -> Files.writeString(file, "{\"PRIVATE_TRUNCATED");
            case "invalid-utf8" -> Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
            case "backwards-time" -> {
                row.put("elapsedMillis", 100); String first = EvidenceJson.write(row) + "\n";
                row.put("elapsedMillis", 99); Files.writeString(file, first + EvidenceJson.write(row) + "\n");
            }
            case "negative-time" -> { row.put("elapsedMillis", -1); Files.writeString(file, EvidenceJson.write(row) + "\n"); }
            default -> fail("Unknown scenario");
        }
        var result = Diagnostics.finish(settings);
        assertFalse(result.passed()); assertTrue(result.json().contains("SAMPLES_INCOMPLETE"));
        assertFalse(result.json().contains("PRIVATE_TRUNCATED"));
    }

    @ParameterizedTest @ValueSource(strings = {"revision", "run", "attempt", "suite", "node"})
    void everyExpectedIdentityComponentBindsTheNativeEvidence(String field) throws Exception {
        var original = successful(); var id = original.identity();
        var expected = new Diagnostics.Identity(field.equals("revision") ? "b".repeat(40) : id.revision(),
                field.equals("run") ? "other-testdata" : id.runId(), field.equals("attempt") ? 2 : id.attempt(),
                field.equals("suite") ? "other" : id.suite(), field.equals("node") ? "2" : id.node());
        var settings = new Diagnostics.Settings(expected, original.directory(), 0, 50, 100, null, null, false, List.of(), "", false);
        var result = Diagnostics.finish(settings);
        assertFalse(result.passed()); assertTrue(result.json().contains("INITIAL_EVIDENCE_INVALID"));
        assertTrue(result.json().contains("PROCESS_RESULT_MISSING_OR_INVALID"));
    }

    @Test void callerMutationCannotChangeDockerConfigurationOrItsFingerprint() {
        var prefix = new ArrayList<>(List.of("docker"));
        var settings = new Diagnostics.Settings(settings(directory.resolve("session")).identity(), directory.resolve("session"),
                0, 50, 100, null, null, false, prefix, "tcp://docker-testdata:2375", true);
        String retained = settings.json(); prefix.add("PRIVATE_NEW_ARGUMENT");
        assertEquals(retained, settings.json()); assertEquals(List.of("docker"), settings.dockerCommand());
        assertThrows(UnsupportedOperationException.class, () -> settings.dockerCommand().clear());
    }

    @Test void configurationAndSessionMustNotOverwriteScopedCounterInputs() throws Exception {
        Path group = Files.createDirectory(directory.resolve("job-testdata"));
        Path counter = group.resolve("memory.current"); Files.writeString(counter, "1234");
        var identity = settings(directory.resolve("session")).identity();
        assertThrows(Diagnostics.Problem.class, () -> new Diagnostics.Settings(identity, group.resolve("nested-session"),
                0, 50, 100, group, null, false, List.of(), "", false));
        var settings = new Diagnostics.Settings(identity, directory.resolve("session"), 0, 50, 100, group, null, false, List.of(), "", false);
        assertThrows(Diagnostics.Problem.class, () -> Diagnostics.prepare(settings, counter));
        assertEquals("1234", Files.readString(counter)); assertFalse(Files.exists(settings.directory()));
    }

    @Test void anUnsafeHtmlDestinationCannotLeaveThePreviousJsonSuccessInPlace() throws Exception {
        var settings = successful(); Path html = settings.directory().resolve("report.html");
        Files.delete(html); Files.createDirectory(html);
        Path unrelated = html.resolve("private.txt"); Files.writeString(unrelated, "PRIVATE_UNCHANGED");
        assertThrows(Diagnostics.Problem.class, () -> Diagnostics.finish(settings));
        assertFalse(Files.exists(settings.directory().resolve("report.json")));
        assertEquals("PRIVATE_UNCHANGED", Files.readString(unrelated));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void replacingAHardLinkedReportDoesNotTruncateTheOtherFile() throws Exception {
        var settings = successful(); Path other = directory.resolve("private.txt"); Files.writeString(other, "PRIVATE_UNCHANGED");
        Path report = settings.directory().resolve("report.json"); Files.delete(report); Files.createLink(report, other);
        assertTrue(Diagnostics.finish(settings).passed());
        assertEquals("PRIVATE_UNCHANGED", Files.readString(other)); assertFalse(Files.isSameFile(report, other));
    }

    private Diagnostics.Settings successful() throws Exception {
        var settings = settings(directory.resolve("session"));
        assertEquals(0, Diagnostics.run(settings, nativeCommand("exit", "0")));
        return settings;
    }
}
