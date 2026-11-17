package io.github.sjacek.shardpilot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Reads real immutable Git blobs; fixtures write loose objects, never run git commit/push. */
class GitImpactTest {
    @TempDir
    Path directory;

    @Test
    void capturesJsonYamlAndChangesInASubdirectoryWithoutReadingWorktreeValues() throws Exception {
        String[] ids = fixture(); var policy = policy(); var impact = policy.profiles().get("impact-testdata").impact();
        var result = GitChanges.acquire(new GitChangeOptions(directory.resolve("src"), ids[0], "HEAD", true, false, "origin", 256, 30), Map.of(), impact);
        assertEquals(GitChanges.Failure.NONE, result.failure()); assertEquals(2, result.changes().contracts().size());
        for (var entry : result.changes().contracts().entrySet()) {
            assertEquals(SelectionImpact.Failure.NONE, entry.getValue().failure());
            assertEquals(Set.of("/paths/~1payments/summary"), entry.getValue().pointers());
            assertNotEquals(entry.getValue().beforeSha256(), entry.getValue().afterSha256());
        }
        assertFalse(result.json().contains("\"summary\":\"after\""));
        Path snapshot = directory.resolve("build/git.json"); Files.writeString(snapshot, result.json());
        assertEquals(result, GitChanges.read(snapshot));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pointer", "before-sha", "after-sha", "format", "failure"})
    void frozenImpactIsRecomputedWithoutQueryingTheMovingBaseAndRejectsTampering(String mutation) throws Exception {
        String[] ids = fixture(); var policy = policy();
        var git = GitChanges.acquire(options(ids[0]), Map.of(), policy.profiles().get("impact-testdata").impact());
        var eligible = Set.of("example.selection.AlphaTest", "example.selection.BetaTest", "example.selection.GammaTest", "example.selection.UnusedTest");
        var context = new SelectionContext("impact-testdata", eligible, git.changes(), Map.of(), Set.of());
        Path source = directory.resolve("build/frozen"), output = directory.resolve("build/runtime");
        var selected = ProfileSelection.prepare(source, policy, context, git, "impact", "", 2);
        assertEquals(3, selected.selected().size()); assertFalse(selected.selected().contains("example.selection.UnusedTest"));
        Files.writeString(directory.resolve(".git/refs/heads/main-testdata"), ids[1] + "\n");
        assertEquals(selected.selected(), ProfileSelection.restore(source, output, policy, "impact-testdata", eligible, Set.of(), "impact", "", 2, directory).selected());
        String original = Files.readString(source.resolve("decision/git.json"));
        var observation = git.changes().contracts().get("contracts/service.json");
        assertNotNull(observation);
        String damaged = switch (mutation) {
            case "pointer" -> original.replace("/paths/~1payments/summary", "/metadata");
            case "before-sha" -> original.replace(observation.beforeSha256(), "c".repeat(64));
            case "after-sha" -> original.replace(observation.afterSha256(), "d".repeat(64));
            case "format" -> original.replace("\"format\":\"JSON\"", "\"format\":\"YAML\"");
            case "failure" -> original.replace("\"failure\":\"NONE\"", "\"failure\":\"READ_FAILED\"");
            default -> throw new AssertionError("Unexpected fixture mutation");
        };
        assertNotEquals(original, damaged);
        Files.writeString(source.resolve("decision/git.json"), damaged);
        assertThrows(IllegalArgumentException.class, () -> ProfileSelection.restore(source, output, policy, "impact-testdata", eligible, Set.of(), "impact", "", 2, directory));
        assertFalse(Files.exists(output.resolve("selection.id")));
    }

    @Test
    void unreadableFrozenMetadataFailsInsteadOfBecomingAFullFallback() throws Exception {
        String[] ids = fixture(); var policy = policy();
        var git = GitChanges.acquire(options(ids[0]), Map.of(), policy.profiles().get("impact-testdata").impact());
        Path file = directory.resolve("build/git.json");
        Files.writeString(file, git.json().replace("\"pointers\":[", "\"PRIVATE_EXTRA\":true,\"pointers\":["));
        var error = assertThrows(IllegalArgumentException.class, () -> GitChanges.read(file));
        assertFalse(error.getMessage().contains("PRIVATE_EXTRA")); assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"impact-invalid", "impact-oversized"})
    void unreadableOrOversizedCommittedContractsRetainOnlyUnknownImpact(String mode) throws Exception {
        String[] ids = fixture(mode); var p = policy();
        var git = GitChanges.acquire(options(ids[0]), Map.of(), p.profiles().get("impact-testdata").impact());
        assertEquals(GitChanges.Failure.NONE, git.failure(), "Path acquisition itself remains complete");
        var observation = git.changes().contracts().get("contracts/service.json"); assertNotNull(observation);
        assertEquals(mode.equals("impact-invalid") ? SelectionImpact.Failure.INVALID_DOCUMENT : SelectionImpact.Failure.RESOURCE_LIMIT, observation.failure());
        assertTrue(observation.pointers().isEmpty()); assertFalse(git.json().contains("PRIVATE_INVALID_INPUT"));
        var context = new SelectionContext("impact-testdata", Set.of("example.selection.AlphaTest", "example.selection.BetaTest", "example.selection.GammaTest"), git.changes(), Map.of(), Set.of());
        assertEquals(SelectionDecision.Status.FAILED, SelectionEngine.select(p, context).status());
        var fallback = SelectionPolicyYaml.parse(SelectionPolicyYaml.format(p).replace("onUnknownImpact: fail", "onUnknownImpact: full"));
        assertEquals(context.eligible(), SelectionEngine.select(fallback, context).selected());
    }

    @Test
    void conflictingConfiguredFormatsCannotSilentlyChooseOneParser() throws Exception {
        String[] ids = fixture();
        // Create a second rule for the same file with the other format; identical bytes cannot resolve this ambiguity.
        var profile = policy().profiles().get("impact-testdata");
        var rules = new java.util.ArrayList<>(profile.impact().contracts());
        rules.add(new SelectionImpact.ContractRule("conflict", Set.of("contracts/*.json"), SelectionImpact.Format.YAML, Set.of(""), SelectionPolicy.Action.FULL, SelectionPolicy.Targets.none(), 0));
        var config = new SelectionImpact.Configuration(profile.impact().changedTests(), profile.impact().modules(), rules);
        var result = GitChanges.acquire(options(ids[0]), Map.of(), config);
        assertEquals(SelectionImpact.Failure.FORMAT_CONFLICT, result.changes().contracts().get("contracts/service.json").failure());
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 33})
    void anObservationBeyondTheFileLimitCannotBeMistakenForCompleteImpact(int fileCount) throws Exception {
        String[] ids = fixture("impact-count-" + fileCount);
        var config = wholeContractImpact();
        var git = GitChanges.acquire(options(ids[0]), Map.of(), config);
        assertEquals(GitChanges.Failure.NONE, git.failure());
        assertEquals(32, git.changes().contracts().size());
        assertTrue(git.changes().contracts().values().stream().allMatch(change -> change.failure() == SelectionImpact.Failure.NONE));
        var result = SelectionEngine.select(impactPolicy(config), context(git));
        assertEquals(fileCount == 32 ? SelectionDecision.Status.PASSED : SelectionDecision.Status.FAILED, result.status());
        if (fileCount == 33) {
            assertTrue(result.reasons().contains(SelectionDecision.Reason.UNKNOWN_IMPACT));
            assertTrue(result.selected().isEmpty());
        } else assertEquals(Set.of("example.selection.BetaTest"), result.selected());
    }

    @Test
    void aggregateBudgetExhaustionRetainsUnknownFactsInsteadOfAcceptingTheFirstEightFiles() throws Exception {
        String[] ids = fixture("impact-budget");
        var config = wholeContractImpact();
        var git = GitChanges.acquire(options(ids[0]), Map.of(), config);
        assertEquals(GitChanges.Failure.NONE, git.failure());
        assertEquals(11, git.changes().contracts().size());
        assertEquals(8, git.changes().contracts().values().stream().filter(change -> change.failure() == SelectionImpact.Failure.NONE).count());
        assertEquals(3, git.changes().contracts().values().stream().filter(change -> change.failure() == SelectionImpact.Failure.RESOURCE_LIMIT).count());
        var result = SelectionEngine.select(impactPolicy(config), context(git));
        assertEquals(SelectionDecision.Status.FAILED, result.status());
        assertTrue(result.reasons().contains(SelectionDecision.Reason.UNKNOWN_IMPACT));
        var full = SelectionPolicyYaml.parse(SelectionPolicyYaml.format(impactPolicy(config)).replace("onUnknownImpact: fail", "onUnknownImpact: full"));
        assertEquals(context(git).eligible(), SelectionEngine.select(full, context(git)).selected());
    }

    private static SelectionImpact.Configuration wholeContractImpact() {
        return SelectionPolicyYaml.parse("""
                schemaVersion: 2
                profiles:
                  impact-testdata:
                    mode: affected
                    impact:
                      contracts:
                        - {id: json, files: ['contracts/*.json'], format: json, pointers: [''], action: require, tests: {classes: [example.selection.BetaTest]}}
                        - {id: yaml, files: ['contracts/*.yaml'], format: yaml, pointers: [''], action: require, tests: {classes: [example.selection.BetaTest]}}
                """).profiles().get("impact-testdata").impact();
    }

    private static SelectionPolicy impactPolicy(SelectionImpact.Configuration config) {
        var original = policy().profiles().get("impact-testdata");
        return new SelectionPolicy(2, Map.of("impact-testdata", new SelectionPolicy.Profile(original.mode(), original.mandatory(),
                original.rules(), original.budget(), original.onUnknownChanges(), original.onUnmatchedChanges(),
                original.onEmptySelection(), config, original.onUnknownImpact())));
    }

    private static SelectionContext context(GitChanges.Result git) {
        return new SelectionContext("impact-testdata", Set.of("example.selection.AlphaTest", "example.selection.BetaTest"), git.changes(), Map.of(), Set.of());
    }

    private GitChangeOptions options(String base) { return new GitChangeOptions(directory, base, "HEAD", true, false, "origin", 256, 30); }

    private String[] fixture(String... modes) throws Exception {
        Path launcher = directory.resolve("build/GitFixture.java"); Files.createDirectories(launcher.getParent());
        try (var resource = getClass().getResourceAsStream("/git-fixture/GitFixture.java")) { assertNotNull(resource); Files.copy(resource, launcher); }
        Files.writeString(directory.resolve(".shardpilot-fixture-testdata"), "synthetic Git fixture\n");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), launcher.toString(), directory.toString(), modes.length == 0 ? "impact" : modes[0])
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output); return output.split("\\R");
    }

    static SelectionPolicy policy() {
        return SelectionPolicyYaml.parse("""
                schemaVersion: 2
                profiles:
                  impact-testdata:
                    mode: affected
                    onUnknownChanges: fail
                    onUnknownImpact: fail
                    onUnmatchedChanges: fail
                    onEmptySelection: fail
                    rules: [{id: legacy, changes: ['src/**', deleted.txt], action: ignore}]
                    impact:
                      changedTests: [{path: src/test/java}]
                      modules:
                        - {id: library, changes: ['src/library/**']}
                        - {id: app, changes: ['src/app/**'], dependsOn: [library], tests: {classes: [example.selection.BetaTest]}}
                      contracts:
                        - {id: json-api, files: ['contracts/*.json'], format: json, pointers: ['/paths/~1payments'], action: require, tests: {classes: [example.selection.GammaTest]}}
                        - {id: yaml-api, files: ['contracts/*.yaml'], format: yaml, pointers: ['/paths/~1payments'], action: require, tests: {classes: [example.selection.GammaTest]}}
                """);
    }
}
