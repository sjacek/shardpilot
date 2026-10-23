package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Format and root-boundary checks use disposable files; consumer bytecode is never loaded. */
class ClassManifestTest {
    @TempDir
    Path root;

    @Test
    void derivesNestedUnicodeBinaryNamesFromBytecodeRoots() throws IOException {
        Path file = root.resolve("example/ŻółćSpec$Nested.class");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        assertEquals(Set.of("example.ŻółćSpec$Nested"), ClassManifest.compiled(List.of(file.toFile()), List.of(root.toFile())));
    }

    @Test
    void rejectsCandidatesOutsideRootsAndNonClassFiles() {
        var outside = root.resolve("outside.class").toFile();
        assertThrows(IllegalArgumentException.class,
                () -> ClassManifest.compiled(List.of(outside), List.of(root.resolve("inside").toFile())));
        assertThrows(IllegalArgumentException.class,
                () -> ClassManifest.compiled(List.of(root.resolve("source.java").toFile()), List.of(root.toFile())));
    }

    @Test
    void rejectsDuplicatedBinaryNameAcrossDifferentCompilationOutputs() {
        var first = root.resolve("java");
        var second = root.resolve("groovy");
        assertThrows(IllegalArgumentException.class, () -> ClassManifest.compiled(
                List.of(first.resolve("SameTest.class").toFile(), second.resolve("SameTest.class").toFile()),
                List.of(first.toFile(), second.toFile())));
    }

    @Test
    void canonicalWriterCreatesParentsAndRoundTripsSortedUtf8Names() throws IOException {
        Path manifest = root.resolve("nested/plan.yaml");
        var names = new LinkedHashSet<>(List.of("example.ŻółćSpec$Nested", "DefaultTest", "example.AlphaTest"));
        ClassManifest.write(manifest, names);
        assertEquals("schemaVersion: 1\nclasses:\n  - DefaultTest\n  - example.AlphaTest\n  - example.ŻółćSpec$Nested\n",
                Files.readString(manifest, StandardCharsets.UTF_8));
        assertEquals(names, ClassManifest.read(manifest, false));
    }

    @Test
    void readsWindowsLineEndingsAndLastRecordWithoutFinalNewline() throws IOException {
        Path manifest = root.resolve("windows.yaml");
        Files.writeString(manifest, "# node selection\r\nclasses: [example.ZetaSpec, example.AlphaTest$Nested]\r\nschemaVersion: 1", StandardCharsets.UTF_8);
        assertEquals(Set.of("example.ZetaSpec", "example.AlphaTest$Nested"), ClassManifest.read(manifest, false));
    }

    @Test
    void emptyShardRequiresOptInAndWriterTruncatesOldEvidence() throws IOException {
        Path manifest = root.resolve("empty.yaml");
        ClassManifest.write(manifest, Set.of("example.PreviousTest"));
        ClassManifest.write(manifest, Set.of());
        assertEquals("schemaVersion: 1\nclasses: []\n", Files.readString(manifest));
        assertEquals(Set.of(), ClassManifest.read(manifest, true));
        assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(manifest, false));
    }

    @Test
    void missingFilesAndDirectoriesCannotRepresentEmptyShards() {
        assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(root.resolve("missing.yaml"), true));
        assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(root, true));
    }

    @Test
    void rejectsDuplicateRecordsWithoutSilentlyDeduplicatingDownloads() throws IOException {
        Path manifest = root.resolve("duplicates.yaml");
        Files.writeString(manifest, ManifestFixtures.yaml("example.AlphaTest", "example.AlphaTest"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(manifest, false))
                .getMessage().contains("Duplicate class"));
    }

    @Test
    void whitespaceAndMalformedClassNamesAreNotSilentlyRepaired() throws IOException {
        Path manifest = root.resolve("invalid.yaml");
        for (String invalid : List.of(" example.Test", "example.Test ", "example..Test",
                "../Test", "example/Test", "example.Test\tstatus", "\ufeffexample.Test", "\nexample.Test")) {
            Files.writeString(manifest, ManifestFixtures.yaml(invalid));
            assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(manifest, false), invalid);
        }
    }

    @Test
    void normalizesRootAndCandidatePathsBeforeCheckingContainment() {
        assertEquals(Set.of("DefaultTest"), ClassManifest.compiled(
                List.of(root.resolve("classes/temporary/../DefaultTest.class").toFile()),
                List.of(root.resolve("unused/../classes").toFile())));
    }

    @Test
    void rejectsUnknownFieldsUnsupportedVersionsAndWrongScalarTypes() throws IOException {
        Path manifest = root.resolve("schema.yaml");
        for (String invalid : List.of("", "[]", "classes: []", "schemaVersion: 1", "schemaVersion: 2\nclasses: []",
                "schemaVersion: '1'\nclasses: []", "schemaVersion: 1.0\nclasses: []", "schemaVersion: true\nclasses: []",
                "schemaVersion: 1\nclasses: []\nextra: true", "schemaVersion: 1\nclasses: null",
                "schemaVersion: 1\nclasses: example.Test", "schemaVersion: 1\nclasses: {}",
                "schemaVersion: 1\nclasses: [123]", "schemaVersion: 1\nclasses: [true]",
                "schemaVersion: 1\nclasses: [null]", "schemaVersion: 1\nclasses: [[example.Test]]")) {
            Files.writeString(manifest, invalid);
            assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(manifest, true), invalid);
        }
    }

    @Test
    void rejectsMalformedYamlDuplicateKeysAndMultipleDocuments() throws IOException {
        Path manifest = root.resolve("syntax.yaml");
        for (String invalid : List.of("schemaVersion: [", "schemaVersion: 1\nschemaVersion: 1\nclasses: []",
                "schemaVersion: 1\nclasses: []\nclasses: []", "schemaVersion: 1\nclasses: []\n---\nclasses: []",
                "schemaVersion: 1\nclasses: !!java/object {}")) {
            Files.writeString(manifest, invalid);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> ClassManifest.read(manifest, true))
                    .getMessage().contains("Invalid YAML manifest"));
        }
    }

    @Test
    void yamlLookingClassNamesRemainStringsAfterWriting() throws IOException {
        Path manifest = root.resolve("scalars.yaml");
        var names = Set.of("true", "null", "False", "example.ŻółćSpec");
        ClassManifest.write(manifest, names);
        assertEquals(names, ClassManifest.read(manifest, false));
    }
}
