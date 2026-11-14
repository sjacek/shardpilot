package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen profiles bind all selection inputs; workers cannot silently replan or broaden a cohort. */
class ProfileSelectionTest {
    @TempDir
    Path directory;
    private static final Set<String> ELIGIBLE = Set.of("example.CoreTest", "example.OtherTest");
    private static final String PROFILE = "smoke-testdata";
    private static final String SUITE = "profile-smoke-testdata";
    private static final SelectionPolicy POLICY = SelectionPolicyYaml.parse("""
            schemaVersion: 1
            profiles:
              smoke-testdata:
                mode: smoke
                mandatory: {classes: [example.CoreTest]}
            """);

    @Test
    void roundTripPreservesReadOnlySourceAndNativeIdentityWithoutRequiringGit() throws Exception {
        Path source = prepare();
        String original = Files.readString(source.resolve("selection.id"));
        var restored = ProfileSelection.restore(source, directory.resolve("runtime"), POLICY, PROFILE, ELIGIBLE,
                Set.of(), SUITE, "", 2, directory);
        assertEquals(Set.of("example.CoreTest"), restored.selected());
        assertEquals(original, Files.readString(directory.resolve("runtime/selection.id")));
        assertEquals(original, Files.readString(source.resolve("selection.id")));
        assertEquals("profile-full", ProfileSelection.defaultSuite("full"));
    }

    @ParameterizedTest
    @MethodSource("incompleteEvidence")
    void missingOrReplacedEvidenceCannotReuseStaleExecutableOutput(String name, boolean missing) throws Exception {
        Path source = prepare();
        Path runtime = directory.resolve("runtime");
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("selected.yaml"), "stale");
        Files.writeString(runtime.resolve("selection.id"), "stale");
        if (missing) Files.delete(source.resolve(name));
        else Files.writeString(source.resolve(name), "corrupt");
        // Missing manifests are domain validation failures; missing raw sidecars are I/O failures.
        // In particular, an unrelated NullPointerException must not satisfy this regression test.
        Class<? extends Exception> failure = missing && !name.endsWith("eligible.yaml") && !name.equals("selected.yaml")
                ? java.io.IOException.class : IllegalArgumentException.class;
        assertThrows(failure, () -> ProfileSelection.restore(source, runtime, POLICY, PROFILE, ELIGIBLE,
                Set.of(), SUITE, "", 2, directory));
        assertFalse(Files.exists(runtime.resolve("selected.yaml")));
        assertFalse(Files.exists(runtime.resolve("selection.id")));
    }

    @Test
    void rejectsChangedPolicyEligibilityHistoryProfileExplicitRequestAndPartitionCount() throws Exception {
        Path source = prepare();
        SelectionPolicy altered = SelectionPolicyYaml.parse("schemaVersion: 1\nprofiles: {smoke-testdata: {mode: full}}\n");
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, altered, PROFILE, ELIGIBLE, Set.of(), SUITE, "", 2));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE, Set.of("example.CoreTest"), Set.of(), SUITE, "", 2));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE, ELIGIBLE, Set.of(), SUITE, "example.CoreTest\t2\n", 2));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, "missing-testdata", ELIGIBLE, Set.of(), SUITE, "", 2));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE, ELIGIBLE, Set.of("example.OtherTest"), SUITE, "", 2));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE, ELIGIBLE, Set.of(), SUITE, "", 4));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE, ELIGIBLE, Set.of(), "another-testdata", "", 2));
    }

    @Test
    void failedDecisionRemovesPriorExecutableSelectionAndRetainsFailureReasons() throws Exception {
        Path source = prepare();
        var failed = SelectionPolicyYaml.parse("schemaVersion: 1\nprofiles: {smoke-testdata: {mode: smoke}}\n");
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.prepare(source, failed, context(), GitChanges.notRequired(), SUITE, "", 2));
        assertFalse(Files.exists(source.resolve("selected.yaml")));
        assertFalse(Files.exists(source.resolve("selection.id")));
        assertTrue(Files.readString(source.resolve("decision/decision.json")).contains("FAILED"));
    }

    @Test
    void provenanceChangesIdentityEvenWhenFullFallbackSelectsTheSameClasses() throws Exception {
        var policy = SelectionPolicyYaml.parse("schemaVersion: 1\nprofiles: {affected-testdata: {mode: affected}}\n");
        var a = new GitChanges.Result(SelectionContext.ChangeSet.unknown(), GitChanges.Source.EXPLICIT, GitChanges.Failure.MISSING_REVISION);
        var b = new GitChanges.Result(SelectionContext.ChangeSet.unknown(), GitChanges.Source.GITLAB_PUSH, GitChanges.Failure.NO_BASE);
        var context = new SelectionContext("affected-testdata", ELIGIBLE, a.changes(), Map.of(), Set.of());
        var first = ProfileSelection.prepare(directory.resolve("first"), policy, context, a, "affected-testdata", "", 1);
        var second = ProfileSelection.prepare(directory.resolve("second"), policy, context, b, "affected-testdata", "", 1);
        assertEquals(first.selected(), second.selected());
        assertNotEquals(first.identity("", 1), second.identity("", 1));
    }

    @Test
    void overlapIsRejectedBeforeSourceArtifactsAreTouched() throws Exception {
        Path source = prepare();
        String original = Files.readString(source.resolve("selection.id"));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.restore(source, source.resolve("runtime"), POLICY,
                PROFILE, ELIGIBLE, Set.of(), SUITE, "", 2, directory));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.protectSnapshot(source, directory));
        assertEquals(original, Files.readString(source.resolve("selection.id")));
    }

    @Test
    void hardLinkedRuntimeOutputCannotModifyTheReadOnlySnapshot() throws Exception {
        Path source = prepare();
        Path runtime = directory.resolve("runtime");
        Files.createDirectories(runtime);
        Files.createLink(runtime.resolve("eligible.yaml"), source.resolve("eligible.yaml"));
        String original = Files.readString(source.resolve("eligible.yaml"));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.restore(source, runtime, POLICY, PROFILE,
                ELIGIBLE, Set.of(), SUITE, "", 2, directory));
        assertEquals(original, Files.readString(source.resolve("eligible.yaml")));
    }

    @Test
    void validYamlWithDifferentSelectedClassesStillFailsCohortValidation() throws Exception {
        Path source = prepare();
        ClassManifest.write(source.resolve("selected.yaml"), Set.of("example.OtherTest"));
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE,
                ELIGIBLE, Set.of(), SUITE, "", 2));
    }

    @Test
    void changedGitProvenanceCannotBeHiddenBehindTheSameUnknownChangeSet() throws Exception {
        Path source = prepare();
        var replaced = new GitChanges.Result(SelectionContext.ChangeSet.unknown(), GitChanges.Source.EXPLICIT, GitChanges.Failure.NO_BASE);
        Files.writeString(source.resolve("decision/git.json"), replaced.json());
        var failure = assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE,
                ELIGIBLE, Set.of(), SUITE, "", 2));
        assertTrue(failure.getMessage().contains("provenance"));
    }

    @Test
    void semanticallyEqualHistoryStillRequiresTheSameRawCohortBytes() throws Exception {
        Path source = directory.resolve("snapshot");
        String history = "example.CoreTest\t2\n";
        var context = new SelectionContext(PROFILE, ELIGIBLE, SelectionContext.ChangeSet.unknown(), DurationHistory.parse(history), Set.of());
        ProfileSelection.prepare(source, POLICY, context, GitChanges.notRequired(), SUITE, history, 2);
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, POLICY, PROFILE,
                ELIGIBLE, Set.of(), SUITE, "example.CoreTest\t2.0\n", 2));
    }

    @Test
    void policyChangesAreRejectedEvenWhenTheyKeepExactlyTheSameSelectedClasses() throws Exception {
        Path source = prepare();
        var changed = SelectionPolicyYaml.parse("""
                schemaVersion: 1
                profiles:
                  smoke-testdata:
                    mode: smoke
                    mandatory: {classes: [example.CoreTest]}
                    budget: {unknownSeconds: 61}
                """);
        assertEquals(SelectionEngine.select(POLICY, context()).selected(), SelectionEngine.select(changed, context()).selected());
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.read(source, changed, PROFILE,
                ELIGIBLE, Set.of(), SUITE, "", 2));
    }

    @Test
    void restorationCopiesOnlyOwnedFilesAndDoesNotModifyAnyInputArtifact() throws Exception {
        Path source = prepare();
        Files.writeString(source.resolve("unowned.txt"), "This is not executable selection evidence.");
        var before = contents(source);
        Path runtime = directory.resolve("runtime");
        ProfileSelection.restore(source, runtime, POLICY, PROFILE, ELIGIBLE, Set.of(), SUITE, "", 2, directory);
        assertEquals(before, contents(source));
        assertFalse(Files.exists(runtime.resolve("unowned.txt")));
        assertEquals(Set.of("example.CoreTest"), ClassManifest.read(runtime.resolve("selected.yaml"), false));
    }

    private static java.util.stream.Stream<Arguments> incompleteEvidence() {
        return java.util.stream.Stream.of("decision/git.json", "decision/decision.id", "decision/decision.json", "decision/decision.html",
                        "decision/eligible.yaml", "decision/selected.yaml", "eligible.yaml", "selected.yaml", "selection.id")
                .flatMap(name -> java.util.stream.Stream.of(Arguments.of(name, false), Arguments.of(name, true)));
    }

    private static Map<String, String> contents(Path source) throws java.io.IOException {
        var result = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(source.relativize(path).toString(), Files.readString(path));
        }
        return result;
    }

    private SelectionContext context() { return new SelectionContext(PROFILE, ELIGIBLE, SelectionContext.ChangeSet.unknown(), Map.of(), Set.of()); }

    private Path prepare() throws Exception {
        Path output = directory.resolve("snapshot");
        ProfileSelection.prepare(output, POLICY, context(), GitChanges.notRequired(), SUITE, "", 2);
        return output;
    }
}
