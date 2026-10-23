package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Both adapters share preparation, pre-write validation and report serialization contracts. */
class ShardManifestsTest {
    @TempDir Path directory;

    @Test
    void preparationAndSelectionUseTheSameFamilyPlannerAndCleanOnlyNumericPlans() throws IOException {
        Set<String> names = Set.of("example.OneTest", "example.OneTest$Nested", "example.TwoSpec");
        String history = "example.OneTest\t8\nexample.TwoSpec\t5\n";
        ShardManifests.prepare(directory, names, history, 2);
        for (int index = 1; index <= 2; index++) {
            assertEquals(ShardManifests.select(names, history, 2, index),
                    ClassManifest.read(directory.resolve("planned-" + index + ".yaml"), true));
        }
        Files.writeString(directory.resolve("metadata.json"), "{}");
        ShardManifests.prepare(directory, names, history, 1);
        assertFalse(Files.exists(directory.resolve("planned-2.yaml")));
        assertTrue(Files.exists(directory.resolve("metadata.json")));
        assertEquals(names, ClassManifest.read(directory.resolve("planned-1.yaml"), false));
    }

    @Test
    void invalidInputsLeaveExistingPlanUntouched() throws IOException {
        ShardManifests.prepare(directory, Set.of("example.OneTest"), "", 1);
        String before = Files.readString(directory.resolve("baseline.yaml"));
        assertThrows(IllegalArgumentException.class, () -> ShardManifests.prepare(directory, Set.of(), "", 1));
        assertThrows(IllegalArgumentException.class, () -> ShardManifests.prepare(directory, Set.of("example.TwoTest"), "bad", 1));
        assertThrows(IllegalArgumentException.class, () -> ShardManifests.prepare(directory, Set.of("example.TwoTest"), "", 0));
        assertEquals(before, Files.readString(directory.resolve("baseline.yaml")));
    }

    @Test
    void independentPlansRetainFamilyOwnershipWithDifferentInputOrderAndEmptyPartitions() throws IOException {
        var names = new LinkedHashSet<>(List.of("example.OneTest", "example.OneTest$Nested", "ŻółćTest", "example.TwoTest"));
        var reversedNames = new ArrayList<>(names);
        Collections.reverse(reversedNames);
        var reversed = new LinkedHashSet<>(reversedNames);
        String history = "example.OneTest$Nested\t7\nexample.TwoTest\t3\n";
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        ShardManifests.prepare(first, names, history, 7);
        ShardManifests.prepare(second, reversed, history, 7);

        var union = new TreeSet<String>();
        int empty = 0;
        for (int index = 1; index <= 7; index++) {
            var planned = ClassManifest.read(first.resolve("planned-" + index + ".yaml"), true);
            assertEquals(planned, ClassManifest.read(second.resolve("planned-" + index + ".yaml"), true));
            assertEquals(planned, ShardManifests.select(reversed, history, 7, index));
            assertTrue(planned.stream().noneMatch(union::contains), "each candidate has exactly one owner");
            assertEquals(planned.contains("example.OneTest"), planned.contains("example.OneTest$Nested"));
            union.addAll(planned);
            if (planned.isEmpty()) empty++;
        }
        assertEquals(names, union);
        assertTrue(empty > 0, "more shards than families must retain explicit empty manifests");
        assertEquals(List.of("example.OneTest", "example.OneTest$Nested", "ŻółćTest", "example.TwoTest"), List.copyOf(names));
    }

    @Test
    void cleanupRetainsUnrelatedFilesAndSelectionDoesNotModifyEligibility() throws IOException {
        for (String name : List.of("planned-0009.yaml", "planned-notes.yaml", "actual-1.yaml", "metadata.json")) {
            Files.writeString(directory.resolve(name), "preserve unless task-owned");
        }
        var names = new LinkedHashSet<>(Set.of("example.OneTest"));
        ShardManifests.prepare(directory, names, "", 1);
        assertFalse(Files.exists(directory.resolve("planned-0009.yaml")));
        for (String name : List.of("planned-notes.yaml", "actual-1.yaml", "metadata.json")) {
            assertEquals("preserve unless task-owned", Files.readString(directory.resolve(name)));
        }
        // Selection is detached: adapters can consume it without changing another node's eligibility.
        ShardManifests.select(names, "", 1, 1).clear();
        assertEquals(Set.of("example.OneTest"), names);
    }

    @Test
    void normalizedReportAliasesFailBeforeReplacingAnEarlierReport() throws IOException {
        Path report = directory.resolve("coverage.json");
        Files.writeString(report, "previous evidence");
        Path alias = directory.resolve("uncreated/../coverage.json");
        assertThrows(IllegalArgumentException.class, () -> ShardCoverage.writeReports(
                directory.resolve("missing-plan"), directory.resolve("missing-actual"), 1, true, true, report, alias));
        assertEquals("previous evidence", Files.readString(report));
        assertFalse(Files.exists(directory.resolve("uncreated")));
    }

    @Test
    void htmlWriteFailureRemainsAnIoErrorEvenAfterSuccessfulJsonWrite() throws IOException {
        Path plan = directory.resolve("plan");
        Path actual = directory.resolve("actual");
        Path json = directory.resolve("reports/coverage.json");
        Path html = Files.createDirectory(directory.resolve("html-is-a-directory"));
        ShardManifests.prepare(plan, Set.of("example.OneTest"), "", 1);
        ClassManifest.write(actual.resolve("actual-1.yaml"), Set.of("example.OneTest"));
        assertThrows(IOException.class, () -> ShardCoverage.writeReports(plan, actual, 1, true, true, json, html));
        assertTrue(Files.readString(json).contains("\"status\":\"passed\""));
        assertTrue(Files.isDirectory(html), "a partial write must not hide or replace the blocking directory");
    }

    @Test
    void failedValidationReplacesBothFormatsAndCoincidentReportPathsAreRejected() throws IOException {
        Path plan = directory.resolve("plan");
        Path actual = directory.resolve("actual");
        Path json = directory.resolve("coverage.json");
        Path html = directory.resolve("coverage.html");
        ShardManifests.prepare(plan, Set.of("example.OneTest"), "", 1);
        ClassManifest.write(actual.resolve("actual-1.yaml"), Set.of("example.OneTest"));
        assertNull(ShardCoverage.writeReports(plan, actual, 1, true, true, json, html).failure());
        Files.delete(actual.resolve("actual-1.yaml"));
        assertNotNull(ShardCoverage.writeReports(plan, actual, 1, true, true, json, html).failure());
        assertTrue(Files.readString(json).contains("\"status\":\"failed\""));
        assertTrue(Files.readString(html).contains("Verification: FAILED"));
        assertThrows(IllegalArgumentException.class, () -> ShardCoverage.writeReports(plan, actual, 1, true, true, json, json));
    }
}
