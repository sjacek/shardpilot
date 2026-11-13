package io.github.sjacek.shardpilot;

import io.github.sjacek.shardpilot.testing.VerifyTestExecutions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proves that the independent oracle cannot accept lost tests, duplicate runs or changed outcomes. */
class VerifyTestExecutionsTest {
    @TempDir
    Path directory;

    @Test
    void identicalRepeatedEventsRemainCountedAndInputOrderDoesNotMatter() throws Exception {
        var repeated = snapshot("baseline", row("repeat", "PASSED") + row("repeat", "PASSED") + row("skip", "SKIPPED"));
        var result = VerifyTestExecutions.compare(repeated, List.of(snapshot("one", row("skip", "SKIPPED")),
                snapshot("two", row("repeat", "PASSED") + row("repeat", "PASSED"))));
        assertTrue(result.passed(), result.json());
        assertEquals(3, result.shardExecutions());
    }

    @Test
    void losingOneInvocationCannotBeHiddenByDistinctIdentitySets() throws Exception {
        var baseline = snapshot("baseline", row("repeat", "PASSED") + row("repeat", "PASSED") + row("other", "PASSED"));
        var result = VerifyTestExecutions.compare(baseline,
                List.of(snapshot("one", row("repeat", "PASSED")), snapshot("two", row("other", "PASSED"))));
        assertFalse(result.passed());
        assertEquals(3, result.baselineExecutions());
        assertEquals(2, result.shardExecutions());
    }

    @Test
    void equalTotalsAndOutcomeCountsDoNotHideDifferentIdentitiesOrSwappedSkips() throws Exception {
        var baseline = snapshot("baseline", row("alpha", "PASSED") + row("beta", "SKIPPED"));
        for (String replacement : List.of(row("gamma", "PASSED") + row("beta", "SKIPPED"),
                row("alpha", "SKIPPED") + row("beta", "PASSED"))) {
            var result = VerifyTestExecutions.compare(baseline,
                    List.of(snapshot("one", replacement), snapshot("two", "")));
            assertFalse(result.passed(), result.json());
            assertEquals(result.baselineExecutions(), result.shardExecutions());
        }
    }

    @Test
    void sameIdentityOnMultipleShardsIsRejectedEvenWhenItsTotalMultiplicityMatches() throws Exception {
        var baseline = snapshot("baseline", row("repeat", "PASSED") + row("repeat", "PASSED"));
        var result = VerifyTestExecutions.compare(baseline,
                List.of(snapshot("one", row("repeat", "PASSED")), snapshot("two", row("repeat", "PASSED"))));
        assertFalse(result.passed());
        assertTrue(result.differences().stream().anyMatch(value -> value.contains("multiple shards")));
    }

    @Test
    void assertionFailureErrorAbortAndSkipAreDifferentTerminalOutcomes() throws Exception {
        var baseline = snapshot("baseline", row("case", "ERROR"));
        for (String outcome : List.of("FAILED", "ABORTED", "SKIPPED", "PASSED")) {
            assertFalse(VerifyTestExecutions.compare(baseline,
                    List.of(snapshot("one", row("case", outcome)), snapshot("two", ""))).passed());
        }
    }

    @Test
    void containerFailuresAreComparedWithoutInflatingTheNumberOfTestInvocations() throws Exception {
        String container = row("class initialization", "ERROR").replace("\tTEST\t", "\tCONTAINER\t");
        var baseline = snapshot("baseline", row("test", "PASSED") + container);
        var changed = VerifyTestExecutions.compare(baseline,
                List.of(snapshot("one", row("test", "PASSED")), snapshot("two", "")));
        assertEquals(1, changed.baselineExecutions());
        assertEquals(1, changed.shardExecutions());
        assertFalse(changed.passed(), "a missing failed container is still an execution difference");
    }

    @Test
    void malformedMissingAndEmptyReferenceEvidenceCannotPass() {
        assertThrows(java.io.IOException.class, () -> VerifyTestExecutions.read(directory.resolve("missing.tsv")));
        assertThrows(IllegalArgumentException.class, () -> VerifyTestExecutions.compare(snapshot("empty", ""), List.of()));
        for (String malformed : List.of("v2\tjunit-platform\tTEST\tYQ\tPASSED\n",
                "v1\tjunit-platform\tTEST\t!bad!\tPASSED\n", row("a", "UNKNOWN"), "\n")) {
            assertThrows(IllegalArgumentException.class, () -> snapshot("invalid", malformed));
        }
    }

    @Test
    void reportRetainsDifferencesAndFailedInputValidationRemovesAnEarlierPassedResult() throws Exception {
        Path baseline = write("baseline", row("alpha", "PASSED"));
        Path one = write("one", row("alpha", "SKIPPED"));
        Path two = write("two", "");
        Path report = directory.resolve("report.json");
        assertFalse(VerifyTestExecutions.verify(report, baseline, List.of(one, two)).passed());
        assertTrue(Files.readString(report).contains("\"status\":\"FAILED\""));
        Files.writeString(report, "{\"status\":\"PASSED\"}");
        assertThrows(IllegalArgumentException.class, () -> VerifyTestExecutions.verify(report, baseline, List.of(one, one)));
        assertFalse(Files.exists(report));
        Files.writeString(report, "{\"status\":\"PASSED\"}");
        assertThrows(java.io.IOException.class,
                () -> VerifyTestExecutions.verify(report, baseline, List.of(one, directory.resolve("missing.tsv"))));
        assertFalse(Files.exists(report));
    }

    @Test
    void reportCannotOverwriteOriginalEvidenceAndBaselineCannotBeReusedAsANode() throws Exception {
        Path baseline = write("baseline", row("alpha", "PASSED"));
        Path empty = write("empty", "");
        assertThrows(IllegalArgumentException.class, () -> VerifyTestExecutions.verify(baseline, baseline, List.of(empty, empty)));
        assertEquals(row("alpha", "PASSED"), Files.readString(baseline));
        assertThrows(IllegalArgumentException.class,
                () -> VerifyTestExecutions.verify(directory.resolve("report.json"), baseline, List.of(baseline, empty)));
    }

    private VerifyTestExecutions.Snapshot snapshot(String name, String contents) throws Exception {
        return VerifyTestExecutions.read(write(name, contents));
    }

    private Path write(String name, String contents) throws Exception {
        Path path = directory.resolve(name + ".tsv");
        Files.writeString(path, contents);
        return path;
    }

    private static String row(String identity, String outcome) {
        return "v1\tjunit-platform\tTEST\t" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(identity.getBytes(StandardCharsets.UTF_8)) + "\t" + outcome + "\n";
    }
}
