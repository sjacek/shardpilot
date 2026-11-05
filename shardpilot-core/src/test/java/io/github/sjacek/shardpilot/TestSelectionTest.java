package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Selection legality, family boundaries and artifact identity are independent of either runner. */
class TestSelectionTest {
    @TempDir
    Path directory;

    @Test
    void selectingNestedMemberExpandsOnlyEligibleFamilyMembersAndFreezesInputs() throws IOException {
        var eligible = new TreeSet<>(Set.of("example.OuterTest", "example.OuterTest$Nested",
                "example.OuterTest$Other", "example.BetaTest"));
        var selection = selection("example.OuterTest$Nested", eligible);
        eligible.clear();
        assertEquals(Set.of("example.OuterTest", "example.OuterTest$Nested", "example.OuterTest$Other"), selection.selected());
        assertEquals(Set.of("example.OuterTest$Nested"), selection.requested());
        assertEquals(4, selection.eligible().size());
        assertThrows(UnsupportedOperationException.class, () -> selection.selected().clear());
    }

    @Test
    void missingOuterIsNeverInventedWhenOnlyNestedBytecodeIsEligible() throws IOException {
        var selected = selection("example.OuterTest$Nested", Set.of("example.OuterTest$Nested"));
        assertEquals(Set.of("example.OuterTest$Nested"), selected.selected());
        assertThrows(IllegalArgumentException.class, () -> selection("example.OuterTest", selected.eligible()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion: 1\nclasses: []", "schemaVersion: 2\nclasses: [example.OneTest]",
            "schemaVersion: 1\nclasses: [example.OneTest, example.OneTest]",
            "schemaVersion: 1\nclasses: [example.MissingTest]", "schemaVersion: 1\nclasses: ['../Test']",
            "schemaVersion: 1\nclasses: [example.OneTest]\nextra: true",
            "schemaVersion: 1\nclasses: [example.OneTest]\nclasses: [example.OneTest]", "schemaVersion: ["})
    void invalidSelectionNeverFallsBackToFull(String yaml) throws IOException {
        Path manifest = directory.resolve("request.yaml");
        Files.writeString(manifest, yaml);
        assertThrows(IllegalArgumentException.class,
                () -> TestSelection.read(manifest, Set.of("example.OneTest"), "smoke"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "full", "FULL", "../smoke", "a/b", "a\\b", "a b", ".", ".."})
    void suiteIdentityCannotEscapeOrReuseFullNamespace(String suite) {
        assertThrows(IllegalArgumentException.class, () -> TestSelection.validateSuite(suite));
    }

    @Test
    void onlySelectedFamiliesAffectPackingAndReportsKeepBothBaselines() throws IOException {
        String history = "example.OuterTest\t2\nexample.OuterTest$Nested\t3\nexample.BetaTest\t1000\n";
        var selection = selection("example.OuterTest", Set.of("example.OuterTest", "example.OuterTest$Nested", "example.BetaTest"));
        var measured = TestSelection.history(history, selection.selected());
        assertEquals(Set.of("example.OuterTest", "example.OuterTest$Nested"), measured.keySet());
        assertEquals(1, ShardPlan.create(measured, 2).shardFor("example.OuterTest"));
        selection.write(directory.resolve("diagnostics"), history, 2);
        String json = Files.readString(directory.resolve("diagnostics/selection.json"));
        assertTrue(json.contains("\"eligibleClasses\":3"));
        assertTrue(json.contains("\"selectedClasses\":2"));
        assertTrue(json.contains("\"familyExpansions\":[\"example.OuterTest$Nested\"]"));
        assertTrue(json.contains("\"estimatedSeconds\":5.0"));
        assertEquals(selection.selected(), ClassManifest.read(directory.resolve("diagnostics/selected.yaml"), false));
        assertEquals(selection.eligible(), ClassManifest.read(directory.resolve("diagnostics/eligible.yaml"), false));
        assertTrue(Files.readString(directory.resolve("diagnostics/selection.html")).contains("Candidate selection does not prove execution success"));
    }

    @Test
    void cohortIdentityDetectsManifestBytesHistoryCountSuiteAndFullEligibilityChanges() throws IOException {
        var selection = selection("example.OneTest", Set.of("example.OneTest"));
        String id = selection.identity("", 1);
        assertEquals(64, id.length());
        assertEquals(id, selection.identity("", 1));
        assertNotEquals(id, selection.identity("", 2));
        assertNotEquals(id, selection.identity("example.OneTest\t1\n", 1));
        Path manifest = directory.resolve("request.yaml");
        Files.writeString(manifest, Files.readString(manifest) + "# same names, new manifest snapshot\n");
        assertNotEquals(id, TestSelection.read(manifest, selection.eligible(), "smoke").identity("", 1));
        assertNotEquals(id, TestSelection.read(manifest, selection.eligible(), "other").identity("", 1));
        assertNotEquals(id, TestSelection.read(manifest, Set.of("example.OneTest", "example.ExtraTest"), "smoke").identity("", 1));
    }

    @Test
    void identicalClassAssignmentsStillRejectMissingSwappedOrStaleSelectionCohorts() throws IOException {
        var selection = selection("example.OneTest", Set.of("example.OneTest", "example.OtherTest"));
        Path plan = directory.resolve("plan");
        Path actual = directory.resolve("actual");
        ShardManifests.prepare(plan, selection.selected(), TestSelection.history("", selection.selected()), 1);
        ClassManifest.write(actual.resolve("actual-1.yaml"), selection.selected());
        Files.writeString(plan.resolve("selection.id"), selection.identity("", 1));
        assertThrows(IllegalArgumentException.class, () -> ShardCoverage.verify(plan, actual, 1, true, true));
        Files.writeString(actual.resolve("selection-1.id"), selection.identity("", 1));
        assertTrue(ShardCoverage.verify(plan, actual, 1, true, true).contains("passed"));
        Files.writeString(actual.resolve("selection-1.id"), selection.identity("", 2));
        var result = ShardCoverage.writeReports(plan, actual, 1, true, true,
                directory.resolve("coverage.json"), directory.resolve("coverage.html"));
        assertNotNull(result.failure());
        assertTrue(result.json().contains("Selection cohort differs"));
        Files.delete(plan.resolve("selection.id"));
        assertThrows(IllegalArgumentException.class, () -> ShardCoverage.verify(plan, actual, 1, true, true));
        Files.delete(actual.resolve("selection-1.id"));
        assertNotNull(ShardCoverage.writeReports(plan, actual, 1, true, true,
                directory.resolve("coverage.json"), directory.resolve("coverage.html"), true).failure());
    }

    @Test
    void selectedFamiliesFormAnExactPartitionIncludingWhenCountIsOne() throws IOException {
        var selection = selection("example.OneTest", Set.of("example.OneTest", "example.OneTest$Nested", "example.OtherTest"));
        for (int count : List.of(1, 3)) {
            Path plan = directory.resolve("plan-" + count);
            ShardManifests.prepare(plan, selection.selected(), TestSelection.history("", selection.selected()), count);
            assertTrue(ShardCoverage.verify(plan, directory.resolve("unused"), count, false, false).contains("passed"));
            assertEquals(selection.selected(), ClassManifest.read(plan.resolve("baseline.yaml"), false));
        }
    }

    @Test
    void failedRefreshOfSelectionReplacesSuccessReportsAndRemovesReusableSnapshot() throws IOException {
        var selection = selection("example.OneTest", Set.of("example.OneTest"));
        Path reports = directory.resolve("reports");
        selection.write(reports, "", 1);
        TestSelection.writeFailure(reports, "invalid <input> & \"quote\"");
        assertFalse(Files.exists(reports.resolve("selected.yaml")));
        assertFalse(Files.exists(reports.resolve("selection.id")));
        assertTrue(Files.readString(reports.resolve("selection.json")).contains("\"status\":\"failed\""));
        assertTrue(Files.readString(reports.resolve("selection.html")).contains("&lt;input&gt; &amp;"));
    }

    @Test
    void maintainedManifestIsProtectedFromOutputAndHardLinkAliases() throws IOException {
        Path source = directory.resolve("selection.yaml");
        ClassManifest.write(source, Set.of("example.OneTest"));
        assertThrows(IllegalArgumentException.class, () -> TestSelection.protectInput(source, source));
        Path alias = directory.resolve("alias.yaml");
        Files.createLink(alias, source);
        assertThrows(IllegalArgumentException.class, () -> TestSelection.protectInput(source, alias));
    }

    /** Direct construction must preserve the same legality boundary as YAML transport. */
    @Test
    void publicSnapshotRejectsPartialFamiliesForeignMembersAndInvalidHashes() {
        var eligible = Set.of("example.OneTest", "example.OneTest$Nested", "example.OtherTest");
        var requested = Set.of("example.OneTest");
        String hash = "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new TestSelection("smoke", hash, eligible, requested, requested));
        assertThrows(IllegalArgumentException.class,
                () -> new TestSelection("smoke", hash, eligible, requested, eligible));
        assertThrows(IllegalArgumentException.class,
                () -> new TestSelection("smoke", hash, eligible, Set.of("example.MissingTest"), requested));
        assertThrows(IllegalArgumentException.class,
                () -> new TestSelection("smoke", hash, eligible, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TestSelection("smoke", hash, Set.of("example..Test"),
                        Set.of("example..Test"), Set.of("example..Test")));
        for (String invalid : List.of("", "a".repeat(63), "A".repeat(64), "x".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TestSelection("smoke", invalid, eligible, requested,
                            Set.of("example.OneTest", "example.OneTest$Nested")));
        }
    }

    @Test
    void directlyConstructedSnapshotCopiesEveryInputSet() {
        var eligible = new TreeSet<>(Set.of("example.OneTest", "example.OtherTest"));
        var requested = new TreeSet<>(Set.of("example.OneTest"));
        var selected = new TreeSet<>(requested);
        var snapshot = new TestSelection("smoke", "a".repeat(64), eligible, requested, selected);
        String id = snapshot.identity("", 1);
        eligible.clear();
        requested.clear();
        selected.clear();
        assertEquals(Set.of("example.OneTest", "example.OtherTest"), snapshot.eligible());
        assertEquals(Set.of("example.OneTest"), snapshot.requested());
        assertEquals(snapshot.requested(), snapshot.selected());
        assertEquals(id, snapshot.identity("", 1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.eligible().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.requested().clear());
    }

    @Test
    void reportAndIdentityAreDeterministicAcrossEligibilityIterationOrders() throws IOException {
        var first = selection("example.OneTest", new LinkedHashSet<>(List.of("example.OneTest", "example.OtherTest")));
        var second = selection("example.OneTest", new LinkedHashSet<>(List.of("example.OtherTest", "example.OneTest")));
        first.write(directory.resolve("first"), "example.OneTest\t1\n", 2);
        second.write(directory.resolve("second"), "example.OneTest\t1\n", 2);
        for (String file : List.of("eligible.yaml", "selected.yaml", "selection.id", "selection.json", "selection.html")) {
            assertEquals(Files.readString(directory.resolve("first/" + file)),
                    Files.readString(directory.resolve("second/" + file)), file);
        }
    }

    @Test
    void malformedUnselectedHistoryCannotBePrunedAwayBeforeValidation() throws IOException {
        var selected = selection("example.OneTest", Set.of("example.OneTest", "example.OtherTest"));
        assertThrows(IllegalArgumentException.class,
                () -> TestSelection.history("example.OneTest\t1\nexample.OtherTest\tNaN\n", selected.selected()));
        assertThrows(IllegalArgumentException.class,
                () -> TestSelection.history("example.OtherTest\t1\nexample.OtherTest\t2\n", selected.selected()));
    }

    @Test
    void zeroMeasuredTimeDiffersFromUnmeasuredFamiliesWithoutInventingDurations() throws IOException {
        var selected = selection("example.OneTest", Set.of("example.OneTest"));
        selected.write(directory.resolve("cold"), "", 1);
        selected.write(directory.resolve("zero"), "example.OneTest\t0\n", 1);
        assertTrue(Files.readString(directory.resolve("cold/selection.json")).contains("\"measuredFamilies\":0"));
        assertTrue(Files.readString(directory.resolve("zero/selection.json")).contains("\"measuredFamilies\":1"));
        for (String path : List.of("cold", "zero")) {
            assertTrue(Files.readString(directory.resolve(path + "/selection.json")).contains("\"estimatedSeconds\":0.0"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "A", "newline"})
    void planOnlyGateRejectsMalformedIdentityEvenWithoutActualEvidence(String input) throws IOException {
        var selected = selection("example.OneTest", Set.of("example.OneTest"));
        Path plan = directory.resolve("plan");
        ShardManifests.prepare(plan, selected.selected(), "", 1);
        String identity = switch (input) {
            case "A" -> "A".repeat(64);
            case "newline" -> selected.identity("", 1) + "\n";
            default -> input;
        };
        Files.writeString(plan.resolve("selection.id"), identity);
        var result = ShardCoverage.writeReports(plan, directory.resolve("missing-actual"), 1, false, true,
                directory.resolve("coverage.json"), directory.resolve("coverage.html"), true);
        assertNotNull(result.failure());
        assertTrue(result.json().contains("Invalid selection cohort identity"));
        assertTrue(Files.readString(directory.resolve("coverage.html")).contains("Verification: FAILED"));
    }

    @Test
    void extraIdentityFromAnOlderShardCountIsNotAcceptedAsAMatchingCohort() throws IOException {
        var selected = selection("example.OneTest", Set.of("example.OneTest"));
        Path plan = directory.resolve("plan");
        Path actual = directory.resolve("actual");
        ShardManifests.prepare(plan, selected.selected(), "", 1);
        Files.writeString(plan.resolve("selection.id"), selected.identity("", 1));
        ClassManifest.write(actual.resolve("actual-1.yaml"), selected.selected());
        Files.writeString(actual.resolve("selection-1.id"), selected.identity("", 1));
        Files.writeString(actual.resolve("selection-2.id"), selected.identity("", 1));
        var result = ShardCoverage.writeReports(plan, actual, 1, true, true,
                directory.resolve("coverage.json"), directory.resolve("coverage.html"), true);
        assertNotNull(result.failure());
        assertTrue(result.json().contains("Unexpected selection identity set"));
        // Plan-only mode deliberately ignores the entire actual directory, including stale evidence.
        assertNull(ShardCoverage.writeReports(plan, actual, 1, false, true,
                directory.resolve("plan-only.json"), directory.resolve("plan-only.html"), true).failure());
    }

    @Test
    void invalidHistoryDoesNotPartiallyReplaceEarlierSelectionEvidence() throws IOException {
        var selected = selection("example.OneTest", Set.of("example.OneTest"));
        Path output = directory.resolve("reports");
        selected.write(output, "", 1);
        String previous = Files.readString(output.resolve("selection.json"));
        assertThrows(IllegalArgumentException.class, () -> selected.write(output, "example.OneTest\tNaN\n", 1));
        assertEquals(previous, Files.readString(output.resolve("selection.json")));
        assertEquals(selected.selected(), ClassManifest.read(output.resolve("selected.yaml"), false));
    }

    @Test
    void finiteIndividualShardLoadsCannotProduceAnInfiniteSelectionEstimate() throws IOException {
        Path manifest = directory.resolve("request.yaml");
        var both = Set.of("example.OneTest", "example.OtherTest");
        ClassManifest.write(manifest, both);
        var selected = TestSelection.read(manifest, both, "smoke");
        Path output = directory.resolve("reports");
        selected.write(output, "", 2);
        String previous = Files.readString(output.resolve("selection.json"));
        String history = "example.OneTest\t1.7E308\nexample.OtherTest\t1.7E308\n";
        // Each bin is finite; only the sum across bins overflows, before any JSON/YAML is replaced.
        assertTrue(Double.isFinite(ShardPlan.create(TestSelection.history(history, both), 2).recordedSeconds(1)));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> selected.write(output, history, 2))
                .getMessage().contains("Selected duration estimate overflow"));
        assertEquals(previous, Files.readString(output.resolve("selection.json")));
    }

    private TestSelection selection(String requested, Set<String> eligible) throws IOException {
        Path manifest = directory.resolve("request.yaml");
        ClassManifest.write(manifest, Set.of(requested));
        return TestSelection.read(manifest, eligible, "smoke");
    }
}
