package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The same byte-level contract protects both build-tool adapters; no application fixtures are loaded. */
class CompiledArtifactsTest {
    private static final String REVISION = "a".repeat(40);
    @TempDir Path directory;
    private Path manifest;

    @BeforeEach void fixture() throws Exception {
        write("compiled/classes/example/Test.class", "bytecode");
        write("compiled/resources/config.json", "PRIVATE_RESOURCE");
        write("compiled/resources/zażółć space.txt", "UTF-8");
        write("compiled/libs/module.jar", "runtime");
        manifest = directory.resolve("metadata/compiled.json");
    }

    @Test void sealsDeterministicPortableMetadataAndVerifiesAllBytesWithoutMutation() throws Exception {
        var first = CompiledArtifacts.seal(inputs(), manifest);
        byte[] original = Files.readAllBytes(manifest);
        assertEquals(4, first.files());
        assertEquals(first, CompiledArtifacts.verify(inputs(), manifest, first.manifestSha256()));
        assertArrayEquals(original, Files.readAllBytes(manifest));
        var reordered = new CompiledArtifacts.Inputs(directory, REVISION, "unit",
                List.of("compiled/resources", "compiled/libs", "compiled/classes"), List.of("compiled/classes"),
                List.of("compiled/resources/config.json"), CompiledArtifacts.Limits.defaults());
        assertEquals(first, CompiledArtifacts.seal(reordered, manifest));
        assertArrayEquals(original, Files.readAllBytes(manifest));
        assertFalse(new String(original, java.nio.charset.StandardCharsets.UTF_8).contains("PRIVATE_RESOURCE"));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "changed", "extra", "extra-directory", "missing-root"})
    void rejectsIncompleteChangedAndStaleExtraOutputs(String kind) throws Exception {
        CompiledArtifacts.seal(inputs(), manifest);
        switch (kind) {
            case "missing" -> Files.delete(directory.resolve("compiled/libs/module.jar"));
            case "changed" -> write("compiled/classes/example/Test.class", "CHANGED!"); // Same length is not integrity.
            case "extra" -> write("compiled/classes/example/Stale.class", "old");
            case "extra-directory" -> Files.createDirectory(directory.resolve("compiled/resources/stale"));
            default -> { Files.delete(directory.resolve("compiled/libs/module.jar")); Files.delete(directory.resolve("compiled/libs")); }
        }
        var failure = assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(directory.toString()));
    }

    @Test void bindsRevisionSuiteAndRequirementsIndependentlyOfTheManifest() throws Exception {
        CompiledArtifacts.seal(inputs(), manifest);
        var wrongRevision = new CompiledArtifacts.Inputs(directory, "b".repeat(40), "unit", inputs().roots(),
                inputs().requiredDirectories(), inputs().requiredFiles(), CompiledArtifacts.Limits.defaults());
        assertEquals("REVISION_MISMATCH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(wrongRevision, manifest, "")).code());
        var wrongSuite = new CompiledArtifacts.Inputs(directory, REVISION, "integration", inputs().roots(),
                inputs().requiredDirectories(), inputs().requiredFiles(), CompiledArtifacts.Limits.defaults());
        assertEquals("SUITE_MISMATCH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(wrongSuite, manifest, "")).code());
        var weaker = new CompiledArtifacts.Inputs(directory, REVISION, "unit", inputs().roots(), List.of(), List.of(), CompiledArtifacts.Limits.defaults());
        assertEquals("POLICY_MISMATCH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(weaker, manifest, "")).code());
    }

    @Test void aWorkerCanRelocateTheSealedCohortWithoutReadingTheProducerTree() throws Exception {
        var sealed = CompiledArtifacts.seal(inputs(), manifest);
        Path worker = directory.resolve("restored-worker");
        try (var paths = Files.walk(directory.resolve("compiled"))) {
            for (Path source : paths.toList()) {
                Path target = worker.resolve(directory.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target);
            }
        }
        Path retained = worker.resolve("metadata/compiled.json"); Files.createDirectories(retained.getParent());
        Files.copy(manifest, retained); byte[] original = Files.readAllBytes(retained);
        // Break the producer only: verification must use worker-relative paths and retained bytes.
        Files.delete(directory.resolve("compiled/resources/config.json"));
        var expected = new CompiledArtifacts.Inputs(worker, REVISION, "unit", inputs().roots(),
                inputs().requiredDirectories(), inputs().requiredFiles(), CompiledArtifacts.Limits.defaults());
        assertEquals(sealed, CompiledArtifacts.verify(expected, retained, sealed.manifestSha256()));
        assertArrayEquals(original, Files.readAllBytes(retained));
    }

    @Test void supportsFullSha256GitRevisionsWithoutTruncatingTheirIdentity() throws Exception {
        String revision = "f".repeat(64);
        var expected = new CompiledArtifacts.Inputs(directory, revision, "unit", inputs().roots(),
                inputs().requiredDirectories(), inputs().requiredFiles(), CompiledArtifacts.Limits.defaults());
        var sealed = CompiledArtifacts.seal(expected, manifest);
        assertEquals(sealed, CompiledArtifacts.verify(expected, manifest, sealed.manifestSha256()));
        assertTrue(Files.readString(manifest).contains("\"revision\":\"" + revision + "\""));
    }

    @ParameterizedTest @ValueSource(ints = {65_535, 65_536, 131_085})
    void hashesEveryStreamChunkIncludingItsLastPartialBuffer(int changedOffset) throws Exception {
        long originalBytes = CompiledArtifacts.seal(inputs(), manifest).bytes();
        byte[] payload = new byte[131_089];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index * 37 + 11);
        Path file = directory.resolve("compiled/resources/payload.bin"); Files.write(file, payload);
        var sealed = CompiledArtifacts.seal(inputs(), manifest);
        assertEquals(originalBytes + payload.length, sealed.bytes()); assertEquals(5, sealed.files());
        assertEquals(sealed, CompiledArtifacts.verify(inputs(), manifest, sealed.manifestSha256()));
        payload[changedOffset] ^= 1; Files.write(file, payload);
        assertEquals("FILE_CONTENT_MISMATCH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, "")).code());
    }

    @Test void verificationRecoversOnlyAfterTheCallerRestoresTheExactSealedBytes() throws Exception {
        var sealed = CompiledArtifacts.seal(inputs(), manifest);
        Path file = directory.resolve("compiled/resources/config.json"); byte[] original = Files.readAllBytes(file);
        Files.writeString(file, "changed by caller");
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
        assertEquals("changed by caller", Files.readString(file), "verification must not repair an input");
        Files.write(file, original);
        assertEquals(sealed, CompiledArtifacts.verify(inputs(), manifest, sealed.manifestSha256()));
    }

    @Test void optionalIndependentlySuppliedDigestDetectsManifestReplacement() throws Exception {
        var sealed = CompiledArtifacts.seal(inputs(), manifest);
        Files.writeString(manifest, Files.readString(manifest) + " ");
        assertEquals("MANIFEST_DIGEST_MISMATCH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, sealed.manifestSha256())).code());
        // Whitespace does not change JSON meaning when the trusted transport, rather than a digest, owns provenance.
        assertEquals(4, CompiledArtifacts.verify(inputs(), manifest, "").files());
    }

    @ParameterizedTest @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", " "})
    void anInvalidExpectedDigestCannotSilentlyDisableTheIndependentHashCheck(String digest) throws Exception {
        CompiledArtifacts.seal(inputs(), manifest); byte[] retained = Files.readAllBytes(manifest);
        assertEquals("INVALID_MANIFEST_DIGEST", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, digest)).code());
        assertArrayEquals(retained, Files.readAllBytes(manifest));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "null", "PRIVATE_BROKEN_JSON", "{\"schemaVersion\":1,\"schemaVersion\":1}"})
    void malformedOrAmbiguousManifestsFailWithoutPayloadDiagnostics(String text) throws Exception {
        Files.createDirectories(manifest.getParent()); Files.writeString(manifest, text);
        var failure = assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
        assertEquals("INVALID_MANIFEST", failure.code()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("PRIVATE"));
    }

    @Test void manifestAllocationsAreBoundedEvenWhenTheByteLimitHasNotBeenReached() throws Exception {
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "[" + "null,".repeat(500_000) + "null]");
        assertEquals("INVALID_MANIFEST", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, "")).code());
    }

    @Test void boundedParserRejectsOversizedNumbersWithoutChangingTheLegacyC2EntryPoint() {
        String number = "9".repeat(129);
        assertThrows(IllegalArgumentException.class, () -> EvidenceJson.parse(number, 500_000));
        assertInstanceOf(java.math.BigDecimal.class, EvidenceJson.parse(number));
    }

    @Test void verifyReappliesLoweredLimitsToAnExistingManifest() throws Exception {
        CompiledArtifacts.seal(inputs(), manifest);
        var d = CompiledArtifacts.Limits.defaults();
        var limited = new CompiledArtifacts.Inputs(directory, REVISION, "unit", inputs().roots(), inputs().requiredDirectories(), inputs().requiredFiles(),
                new CompiledArtifacts.Limits(d.maxFiles(), d.maxEntries(), d.maxDepth(), 1, d.maxTotalBytes(), d.maxManifestBytes()));
        assertEquals("RESOURCE_LIMIT", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(limited, manifest, "")).code());
    }

    @ParameterizedTest @ValueSource(strings = {"short", "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "HEAD", "main", "a b"})
    void theExpectedRevisionMustBeAnExactCommitRatherThanAMutableRef(String revision) {
        assertThrows(CompiledArtifacts.Problem.class, () -> new CompiledArtifacts.Inputs(directory, revision, "unit", inputs().roots(), List.of(), List.of(), CompiledArtifacts.Limits.defaults()));
    }

    @Test void unknownManifestFieldsAndNoncanonicalVersionsCannotBeIgnored() throws Exception {
        CompiledArtifacts.seal(inputs(), manifest); String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unexpected\":true"));
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
        Files.writeString(manifest, original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
    }

    @Test void failedResealInvalidatesEarlierSuccessWithoutRepairingMissingInputs() throws Exception {
        CompiledArtifacts.seal(inputs(), manifest);
        Files.delete(directory.resolve("compiled/resources/config.json"));
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), manifest));
        assertFalse(Files.exists(manifest)); assertFalse(Files.exists(directory.resolve("compiled/resources/config.json")));
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.verify(inputs(), manifest, ""));
        assertFalse(Files.exists(manifest));
    }

    @Test void requiredFilesAndDirectoriesCannotBeSatisfiedWithEmptyOutputs() throws Exception {
        write("compiled/classes/example/Test.class", "");
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), manifest));
        write("compiled/classes/example/Test.class", "ok"); write("compiled/resources/config.json", "");
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), manifest));
    }

    @ParameterizedTest @ValueSource(strings = {"../escape", "/absolute", "C:/drive", "compiled\\classes", "compiled//classes", "compiled/./classes", "compiled/../classes", "compiled/CON", "compiled/trailing.", "compiled/trailing "})
    void rejectsUnsafeAndNonportableConfiguredPaths(String path) {
        assertThrows(CompiledArtifacts.Problem.class, () -> new CompiledArtifacts.Inputs(directory, REVISION, "unit", List.of(path), List.of(), List.of(), CompiledArtifacts.Limits.defaults()));
    }

    @Test void rejectsOverlappingRootsAndRequirementsOutsideDeclaredTrees() {
        assertThrows(CompiledArtifacts.Problem.class, () -> new CompiledArtifacts.Inputs(directory, REVISION, "unit",
                List.of("compiled", "compiled/classes"), List.of(), List.of(), CompiledArtifacts.Limits.defaults()));
        assertThrows(CompiledArtifacts.Problem.class, () -> new CompiledArtifacts.Inputs(directory, REVISION, "unit",
                List.of("compiled/classes"), List.of(), List.of("private/input"), CompiledArtifacts.Limits.defaults()));
        assertEquals("OVERLAPPING_ROOTS", assertThrows(CompiledArtifacts.Problem.class,
                () -> new CompiledArtifacts.Inputs(directory, REVISION, "unit", List.of("compiled", "COMPILED/resources"),
                        List.of(), List.of(), CompiledArtifacts.Limits.defaults())).code());
    }

    @Test void manifestCannotOverwriteOrBecomePartOfInventoriedInputs() throws Exception {
        Path target = directory.resolve("compiled/resources/config.json"); String original = Files.readString(target);
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), target));
        assertEquals(original, Files.readString(target));
    }

    @Test void anExistingDirectoryAtTheManifestPathIsPreservedInsteadOfBeingCleaned() throws Exception {
        Files.createDirectories(manifest); Path sentinel = manifest.resolve("owned-by-caller.txt");
        Files.writeString(sentinel, "preserve");
        assertEquals("UNSAFE_MANIFEST_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.seal(inputs(), manifest)).code());
        assertEquals("UNSAFE_MANIFEST_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, "")).code());
        assertEquals("preserve", Files.readString(sentinel));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void aManifestSymlinkIsRejectedBeforeInvalidatingTheDestinationOrReadingItsTarget() throws Exception {
        Files.createDirectories(manifest.getParent()); Path target = directory.resolve("compiled/resources/config.json");
        String original = Files.readString(target); Files.createSymbolicLink(manifest, target);
        assertEquals("UNSAFE_ARTIFACT_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.seal(inputs(), manifest)).code());
        assertEquals("UNSAFE_ARTIFACT_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.verify(inputs(), manifest, "")).code());
        assertTrue(Files.isSymbolicLink(manifest)); assertEquals(original, Files.readString(target));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void caseCollidingArtifactNamesFailBeforePublishingAnAmbiguousPortableInventory() throws Exception {
        write("compiled/resources/Case.txt", "first"); write("compiled/resources/case.txt", "second");
        assertEquals("AMBIGUOUS_ARTIFACT_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.seal(inputs(), manifest)).code());
        assertFalse(Files.exists(manifest));
    }

    @ParameterizedTest @ValueSource(strings = {"files", "entries", "depth", "fileBytes", "totalBytes", "manifestBytes"})
    void enforcesLoweredResourceLimits(String limit) {
        var d = CompiledArtifacts.Limits.defaults();
        var limits = new CompiledArtifacts.Limits(limit.equals("files") ? 1 : d.maxFiles(), limit.equals("entries") ? 3 : d.maxEntries(),
                limit.equals("depth") ? 1 : d.maxDepth(), limit.equals("fileBytes") ? 1 : d.maxFileBytes(),
                limit.equals("totalBytes") ? 1 : d.maxTotalBytes(), limit.equals("manifestBytes") ? 1 : d.maxManifestBytes());
        var constrained = new CompiledArtifacts.Inputs(directory, REVISION, "unit", inputs().roots(), inputs().requiredDirectories(), inputs().requiredFiles(), limits);
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(constrained, manifest));
        assertFalse(Files.exists(manifest));
    }

    @Test void rejectsAliasesWithinTheInventory() throws Exception {
        Files.createLink(directory.resolve("compiled/resources/alias"), directory.resolve("compiled/libs/module.jar"));
        assertEquals("UNSAFE_ARTIFACT_ALIAS", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.seal(inputs(), manifest)).code());
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsSymlinksCyclesAndAncestorLinksWithoutFollowingThem() throws Exception {
        Path link = directory.resolve("compiled/resources/cycle");
        Files.createSymbolicLink(link, directory.resolve("compiled/resources"));
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), manifest));
        Files.delete(link);
        Path external = directory.resolve("external"); Files.createDirectories(external);
        Files.createSymbolicLink(directory.resolve("ancestor"), external);
        var linked = new CompiledArtifacts.Inputs(directory.resolve("ancestor"), REVISION, "unit", List.of("classes"), List.of(), List.of(), CompiledArtifacts.Limits.defaults());
        assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(linked, manifest));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void specialFilesAreRejectedWithoutOpeningABlockingStream() throws Exception {
        assertEquals(0, new ProcessBuilder("mkfifo", directory.resolve("compiled/resources/pipe").toString()).start().waitFor());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertThrows(CompiledArtifacts.Problem.class, () -> CompiledArtifacts.seal(inputs(), manifest)));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionAncestorsCannotRedirectTheConfiguredArtifactRoot() throws Exception {
        Path base = directory.resolve("junction-workspace"), target = directory.resolve("junction-target");
        Files.createDirectories(base); Files.createDirectories(target.resolve("classes"));
        Files.writeString(target.resolve("classes/Example.class"), "bytecode");
        Path junction = base.resolve("downloaded");
        var command = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, command.waitFor(), output);
        var redirected = new CompiledArtifacts.Inputs(base, REVISION, "unit", List.of("downloaded/classes"), List.of(), List.of(), CompiledArtifacts.Limits.defaults());
        assertEquals("UNSAFE_ARTIFACT_PATH", assertThrows(CompiledArtifacts.Problem.class,
                () -> CompiledArtifacts.seal(redirected, manifest)).code());
        assertEquals("bytecode", Files.readString(target.resolve("classes/Example.class")));
    }

    private CompiledArtifacts.Inputs inputs() {
        return new CompiledArtifacts.Inputs(directory, REVISION, "unit", List.of("compiled/classes", "compiled/resources", "compiled/libs"),
                List.of("compiled/classes"), List.of("compiled/resources/config.json"), CompiledArtifacts.Limits.defaults());
    }
    private void write(String path, String text) throws Exception {
        Path file = directory.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
}
