package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Generic artifact contracts include missing evidence and wrong-node downloads, not just union size. */
class ShardCoverageTest {
    @TempDir
    Path directory;
    Path plan;
    Path actual;

    @BeforeEach
    void fixture() throws Exception {
        plan = Files.createDirectory(directory.resolve("plan"));
        actual = Files.createDirectory(directory.resolve("actual"));
        write(plan, "baseline", "example.OneSpec", "example.TwoTest", "example.ThreeIT$Nested");
        for (int index = 1; index <= 3; index++) {
            String name = List.of("example.OneSpec", "example.TwoTest", "example.ThreeIT$Nested").get(index - 1);
            write(plan, "planned-" + index, name);
            write(actual, "actual-" + index, name);
        }
    }

    @Test
    void verifiesGenericAndNestedNamesWithoutAnyApplicationSuffixConvention() throws Exception {
        String report = verify();
        assertTrue(report.contains("\"classes\":3"));
        assertTrue(report.contains("\"actualVerified\":true"));
        assertTrue(report.contains("\"sha256\":"));
    }

    @Test
    void canVerifyOnlyPlanWithoutRequiringActualFiles() throws Exception {
        assertTrue(ShardCoverage.verify(plan, directory.resolve("missing"), 3, false, true)
                .contains("\"actualVerified\":false"));
    }

    @Test
    void rejectsOmittedAndForeignClasses() throws Exception {
        write(plan, "planned-3", "example.ForeignTest");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ShardCoverage.verify(plan, actual, 3, false, true)).getMessage().contains("Incomplete partition"));
    }

    @Test
    void rejectsOverlappingShardsAndDuplicateLines() throws Exception {
        write(plan, "planned-2", "example.OneSpec");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ShardCoverage.verify(plan, actual, 3, false, true)).getMessage().contains("multiple shards"));
        write(plan, "planned-1", "example.OneSpec", "example.OneSpec");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Duplicate class"));
    }

    @Test
    void rejectsSwappedActualPartitionsEvenWhenUnionMatches() throws Exception {
        write(actual, "actual-1", "example.TwoTest");
        write(actual, "actual-2", "example.OneSpec");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Shard 1 differs"));
    }

    @Test
    void rejectsMissingEvidence() throws Exception {
        Files.delete(actual.resolve("actual-3.yaml"));
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Missing class manifest"));
    }

    @Test
    void rejectsStaleExtraArtifacts() throws Exception {
        write(actual, "actual-4", "example.ExtraTest");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Unexpected actual"));
        Files.delete(actual.resolve("actual-4.yaml"));
        write(plan, "planned-4", "example.ExtraTest");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Unexpected planned"));
    }

    @Test
    void rejectsEmptyBaselineAndInvalidNames() throws Exception {
        for (String invalid : List.of("../outside", "example.Test\tInjected", " example.Test", "")) {
            write(plan, "baseline", invalid);
            assertThrows(IllegalArgumentException.class, this::verify);
        }
        write(plan, "baseline");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Empty class manifest"));
    }

    @Test
    void emptyShardRequiresExplicitOptOut() throws Exception {
        write(plan, "baseline", "example.OneSpec", "example.TwoTest");
        write(plan, "planned-3");
        write(actual, "actual-3");
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Empty class manifest"));
        assertTrue(ShardCoverage.verify(plan, actual, 3, true, false).contains("\"classes\":0"));
    }

    @Test
    void invalidPartitionCountCannotPass() {
        assertThrows(IllegalArgumentException.class, () -> ShardCoverage.verify(plan, actual, 0, true, true));
    }

    @Test
    void failureReportEscapesUntrustedMetadataAsJson() {
        assertEquals("{\"status\":\"failed\",\"error\":\"bad\\\"\\\\\\u000a\"}\n", ShardCoverage.failed("bad\"\\\n"));
    }

    @Test
    void canonicalReportAndHashesIgnoreRecordOrderAndPlatformLineEndings() throws IOException {
        Files.writeString(plan.resolve("planned-1.yaml"), ManifestFixtures.yaml("example.OneSpec", "example.TwoTest"));
        Files.writeString(actual.resolve("actual-1.yaml"), ManifestFixtures.yaml("example.TwoTest", "example.OneSpec"));
        Files.writeString(plan.resolve("planned-2.yaml"), ManifestFixtures.yaml("example.ThreeIT$Nested"));
        Files.writeString(actual.resolve("actual-2.yaml"), ManifestFixtures.yaml("example.ThreeIT$Nested"));
        Files.writeString(plan.resolve("planned-3.yaml"), ManifestFixtures.yaml());
        Files.writeString(actual.resolve("actual-3.yaml"), ManifestFixtures.yaml());
        String first = ShardCoverage.verify(plan, actual, 3, true, false);
        Files.writeString(plan.resolve("planned-1.yaml"), ManifestFixtures.yaml("example.TwoTest", "example.OneSpec").replace("\n", "\r\n").stripTrailing());
        Files.writeString(actual.resolve("actual-1.yaml"), ManifestFixtures.yaml("example.OneSpec", "example.TwoTest").replace("\n", "\r\n").stripTrailing());
        assertEquals(first, ShardCoverage.verify(plan, actual, 3, true, false));
        assertTrue(first.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "empty shard uses the standard SHA-256 of an empty byte sequence");
    }

    @Test
    void missingBaselineOrPlannedShardCannotPassEvenWithoutActualVerification() throws IOException {
        Files.delete(plan.resolve("baseline.yaml"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ShardCoverage.verify(plan, actual, 3, false, true)).getMessage().contains("baseline.yaml"));
        Files.writeString(plan.resolve("baseline.yaml"), ManifestFixtures.yaml("example.OneSpec", "example.TwoTest", "example.ThreeIT$Nested"));
        Files.delete(plan.resolve("planned-2.yaml"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ShardCoverage.verify(plan, actual, 3, false, true)).getMessage().contains("planned-2.yaml"));
    }

    @Test
    void missingAndForeignActualClassesAreReportedTogether() throws IOException {
        Files.writeString(actual.resolve("actual-1.yaml"), ManifestFixtures.yaml("example.ForeignTest"));
        String message = assertThrows(IllegalArgumentException.class, this::verify).getMessage();
        assertTrue(message.contains("missing=[example.OneSpec]"));
        assertTrue(message.contains("unexpected=[example.ForeignTest]"));
    }

    @Test
    void duplicateActualRecordsAreRejectedBeforeSetComparison() throws IOException {
        Files.writeString(actual.resolve("actual-1.yaml"), ManifestFixtures.yaml("example.OneSpec", "example.OneSpec"));
        String message = assertThrows(IllegalArgumentException.class, this::verify).getMessage();
        assertTrue(message.contains("Duplicate class in actual-1.yaml"));
    }

    @Test
    void unrelatedFilesDoNotCountAsExtraShardsButMalformedIndexedArtifactsDo() throws IOException {
        Files.writeString(plan.resolve("README.md"), "Planning metadata");
        Files.writeString(actual.resolve("memory.log"), "Worker diagnostics");
        assertDoesNotThrow(this::verify);
        Files.writeString(actual.resolve("actual-old.yaml"), ManifestFixtures.yaml("example.OneSpec"));
        assertTrue(assertThrows(IllegalArgumentException.class, this::verify).getMessage().contains("Unexpected actual"));
    }

    @Test
    void planOnlyModeDoesNotInspectStaleOrInvalidActualDownloads() throws IOException {
        Files.writeString(actual.resolve("actual-1.yaml"), "invalid metadata\n");
        Files.writeString(actual.resolve("actual-99.yaml"), "stale metadata\n");
        assertTrue(ShardCoverage.verify(plan, actual, 3, false, true).contains("\"actualVerified\":false"));
    }

    @Test
    void failureReportEscapesAllControlCharactersAndPreservesUnicode() {
        var payload = new StringBuilder("Żółć: ");
        for (char character = 0; character < 32; character++) payload.append(character);
        String report = ShardCoverage.failed(payload.toString());
        assertTrue(report.contains("Żółć: "));
        assertTrue(report.contains("\\u0000"));
        assertTrue(report.contains("\\u001f"));
        assertEquals(1, report.chars().filter(character -> character < 32).count(),
                "the only unescaped control character is the report's final newline");
    }

    private String verify() throws IOException { return ShardCoverage.verify(plan, actual, 3, true, true); }

    private static void write(Path directory, String name, String... classes) throws IOException {
        Files.writeString(directory.resolve(name + ".yaml"), ManifestFixtures.yaml(classes));
    }
}
