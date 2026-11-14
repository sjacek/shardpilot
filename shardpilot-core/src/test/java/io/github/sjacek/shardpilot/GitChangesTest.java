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

/** Real local Git reads use synthetic loose objects, without creating commits in the developer repo. */
class GitChangesTest {
    @TempDir
    Path directory;

    @Test
    void capturesExactCommitsAndBothRenamePathsIncludingWhitespace() throws Exception {
        var ids = fixture();
        var result = GitChanges.acquire(options(ids[0]), Map.of());
        assertEquals(GitChanges.Failure.NONE, result.failure());
        assertEquals(GitChanges.Source.EXPLICIT, result.source());
        assertEquals(ids[0], result.changes().baseRevision());
        assertEquals(ids[1], result.changes().headRevision());
        assertEquals(Set.of("deleted.txt", "src/old name.txt", "src/new name.txt", "src/modified.txt", "src/addition.txt"), result.changes().paths());
        Path saved = directory.resolve("build/git.json");
        Files.writeString(saved, result.json());
        assertEquals(result, GitChanges.read(saved));
    }

    @Test
    void observedEmptyDiffIsKnownWhileMissingHistoryIsUnknown() throws Exception {
        var ids = fixture();
        var empty = GitChanges.acquire(options(ids[1]), Map.of());
        assertEquals(SelectionContext.Availability.KNOWN, empty.changes().availability());
        assertTrue(empty.changes().paths().isEmpty());
        var missing = GitChanges.acquire(options("f".repeat(40)), Map.of());
        assertEquals(GitChanges.Failure.MISSING_REVISION, missing.failure());
        assertEquals(SelectionContext.Availability.UNKNOWN, missing.changes().availability());
        assertEquals(ids[1], missing.changes().headRevision());
    }

    @Test
    void infersGitlabBaseAndRejectsDirtyOrNewBranchInputsWithoutInventingAParent() throws Exception {
        var ids = fixture();
        var request = GitChangeOptions.defaults(directory);
        assertEquals(GitChanges.Source.GITLAB_MERGE_REQUEST,
                GitChanges.acquire(request, Map.of("CI_MERGE_REQUEST_DIFF_BASE_SHA", ids[0])).source());
        assertEquals(GitChanges.Failure.NO_BASE,
                GitChanges.acquire(request, Map.of("CI_COMMIT_BEFORE_SHA", "0".repeat(40))).failure());
        Files.writeString(directory.resolve("src/modified.txt"), "uncommitted\n");
        assertEquals(GitChanges.Failure.DIRTY_WORKTREE, GitChanges.acquire(options(ids[0]), Map.of()).failure());
        assertThrows(IllegalArgumentException.class, () -> GitChanges.verifyCheckout(directory, ids[1], true));
    }

    @Test
    void infersGithubPayloadAndKeepsExplicitOverrideAuthoritative() throws Exception {
        var ids = fixture();
        Path event = directory.resolve("build/event.json");
        Files.writeString(event, "{\"pull_request\":{\"base\":{\"sha\":\"" + ids[0] + "\"}}}");
        var env = Map.of("GITHUB_EVENT_NAME", "pull_request", "GITHUB_EVENT_PATH", event.toString());
        var inferred = GitChanges.acquire(GitChangeOptions.defaults(directory), env);
        assertEquals(GitChanges.Source.GITHUB_PULL_REQUEST, inferred.source());
        assertEquals(GitChanges.Failure.NONE, inferred.failure());
        var explicit = GitChanges.acquire(options(ids[1]), env);
        assertEquals(GitChanges.Source.EXPLICIT, explicit.source());
        assertTrue(explicit.changes().paths().isEmpty());
    }

    @Test
    void rejectsAnExplicitHeadDifferentFromTheCheckout() throws Exception {
        var ids = fixture();
        assertThrows(IllegalArgumentException.class, () -> GitChanges.acquire(new GitChangeOptions(directory,
                ids[0], ids[0], true, false, "origin", 256, 30), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> GitChanges.verifyCheckout(directory, ids[0], false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=bad", "a..b", "a:b", "a b", "a\nb", "a\\b", ""})
    void rejectsUnsafeExplicitRefsBeforeAnyProcess(String ref) {
        assertThrows(IllegalArgumentException.class, () -> new GitChangeOptions(directory, "", ref, true, false, "origin", 256, 30));
    }

    @Test
    void parsesCopyDeleteAndRenameRecordsWithoutShellOrWhitespaceSplitting() {
        assertEquals(Set.of("a file", "b file", "removed", "Żółć.txt"), GitChanges.names(
                "C100\0a file\0b file\0D\0removed\0M\0Żółć.txt\0".getBytes(StandardCharsets.UTF_8)));
        for (String malformed : new String[]{"M\0missing-final-nul", "R100\0old\0", "Z\0a\0", "M\0../outside\0"}) {
            assertThrows(RuntimeException.class, () -> GitChanges.names(malformed.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void mergeRequestIncludesEarlierCommitsAndMissingShallowHistoryNeedsExplicitFetch() throws Exception {
        var ids = fixture();
        var lastCommit = GitChanges.acquire(options("HEAD^"), Map.of());
        assertFalse(lastCommit.changes().paths().contains("src/old name.txt"));
        assertTrue(GitChanges.acquire(options(ids[0]), Map.of()).changes().paths().contains("src/old name.txt"));
        Path clone = directory.resolve("build/shallow-testdata");
        Files.createDirectories(clone.getParent());
        command("git", "clone", "--quiet", "--depth=1", "--branch=feature-testdata", directory.toUri().toString(), clone.toString());
        var cold = new GitChangeOptions(clone, ids[0], "HEAD", true, false, "origin", 256, 30);
        assertEquals(GitChanges.Failure.MISSING_REVISION, GitChanges.acquire(cold, Map.of()).failure());
        var fetched = new GitChangeOptions(clone, ids[0], "HEAD", true, true, "origin", 256, 30);
        assertEquals(GitChanges.acquire(options(ids[0]), Map.of()).changes(), GitChanges.acquire(fetched, Map.of()).changes());
    }

    @Test
    void rootRelativeDiffIgnoresGitRelativeConfigurationAndFrozenHeadMustStillMatch() throws Exception {
        var ids = fixture();
        command("git", "-C", directory.toString(), "config", "diff.relative", "true");
        var nested = new GitChangeOptions(directory.resolve("src"), ids[0], "HEAD", true, false, "origin", 256, 30);
        assertTrue(GitChanges.acquire(nested, Map.of()).changes().paths().contains("deleted.txt"));
        Path snapshot = directory.resolve("build/snapshot.json");
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, GitChanges.acquire(nested, Map.of()).json());
        GitChanges.verifyCheckout(directory, ids[1], true);
        Files.writeString(directory.resolve("new-untracked.txt"), "untracked");
        assertEquals(GitChanges.Failure.DIRTY_WORKTREE, GitChanges.acquire(nested, Map.of()).failure());
    }

    @Test
    void rejectsCorruptSnapshotsAndOversizedPoliciesWithoutEchoingTheirContent() throws Exception {
        Path file = directory.resolve("invalid.json");
        for (String text : new String[]{"{}", "{schemaVersion: 1, schemaVersion: 1}", "!unsafe secret-sentinel", "[".repeat(100)}) {
            Files.writeString(file, text);
            var error = assertThrows(IllegalArgumentException.class, () -> GitChanges.read(file));
            assertFalse(error.getMessage().contains("secret-sentinel"));
        }
        Files.writeString(file, "a".repeat(1_048_577));
        assertThrows(IllegalArgumentException.class, () -> SelectionPolicyYaml.read(file));
        Files.write(file, new byte[]{(byte) 0xff});
        assertThrows(java.io.IOException.class, () -> SelectionPolicyYaml.read(file));
    }

    @Test
    void comparesDivergedTargetAgainstItsAncestorUnlessDirectComparisonWasRequested() throws Exception {
        var ids = fixture();
        String target = "refs/remotes/origin/main-testdata";
        var ancestor = GitChanges.acquire(options(target), Map.of());
        var direct = GitChanges.acquire(new GitChangeOptions(directory, target, "HEAD", false, false, "origin", 256, 30), Map.of());
        assertEquals(GitChanges.Failure.NONE, ancestor.failure());
        assertEquals(GitChanges.Failure.NONE, direct.failure());
        assertEquals(ids[0], ancestor.changes().baseRevision());
        assertNotEquals(ids[0], direct.changes().baseRevision());
        assertEquals(ancestor.changes().paths(), direct.changes().paths());
        // The merge has the same tree but two parents; acquisition must retain its actual revision.
        command("git", "-C", directory.toString(), "update-ref", "refs/heads/feature-testdata", "refs/heads/merged-testdata");
        var merged = GitChanges.acquire(options(ids[0]), Map.of());
        assertEquals(GitChanges.Failure.NONE, merged.failure());
        assertNotEquals(ids[1], merged.changes().headRevision());
        assertEquals(ancestor.changes().paths(), merged.changes().paths());
    }

    @Test
    void ciBranchFallbackAndPushMetadataUseTheSameCompleteDiff() throws Exception {
        var ids = fixture();
        var defaults = GitChangeOptions.defaults(directory);
        var mr = GitChanges.acquire(defaults, Map.of("CI_MERGE_REQUEST_TARGET_BRANCH_NAME", "main-testdata",
                "CI_COMMIT_BEFORE_SHA", ids[1]));
        assertEquals(GitChanges.Source.GITLAB_MERGE_REQUEST, mr.source());
        assertEquals(ids[0], mr.changes().baseRevision(), "MR ancestry takes precedence over the push before-SHA");
        var push = GitChanges.acquire(defaults, Map.of("CI_COMMIT_BEFORE_SHA", ids[0]));
        assertEquals(GitChanges.Source.GITLAB_PUSH, push.source());
        assertEquals(mr.changes(), push.changes());
        var pr = GitChanges.acquire(defaults, Map.of("GITHUB_EVENT_NAME", "pull_request", "GITHUB_BASE_REF", "main-testdata"));
        assertEquals(GitChanges.Source.GITHUB_PULL_REQUEST, pr.source());
        assertEquals(mr.changes(), pr.changes());
        Path event = directory.resolve("build/push.json");
        Files.writeString(event, "{\"before\":\"" + ids[0] + "\"}");
        var github = GitChanges.acquire(defaults, Map.of("GITHUB_EVENT_NAME", "push", "GITHUB_EVENT_PATH", event.toString()));
        assertEquals(GitChanges.Source.GITHUB_PUSH, github.source());
        assertEquals(mr.changes(), github.changes());
    }

    @Test
    void malformedCiMetadataAndUnknownEventsCannotBecomeSuccessfulEmptyDiffs() throws Exception {
        fixture();
        Path event = directory.resolve("build/invalid-event.json");
        Files.writeString(event, "!unsafe secret-sentinel");
        var invalid = GitChanges.acquire(GitChangeOptions.defaults(directory),
                Map.of("GITHUB_EVENT_NAME", "push", "GITHUB_EVENT_PATH", event.toString()));
        assertEquals(GitChanges.Failure.INVALID_INPUT, invalid.failure());
        assertEquals(SelectionContext.Availability.UNKNOWN, invalid.changes().availability());
        assertFalse(invalid.json().contains("secret-sentinel"));
        var unsupported = GitChanges.acquire(GitChangeOptions.defaults(directory),
                Map.of("GITHUB_EVENT_NAME", "workflow_dispatch", "GITHUB_EVENT_PATH", event.toString()));
        assertEquals(GitChanges.Failure.NO_BASE, unsupported.failure());
        assertEquals(SelectionContext.Availability.UNKNOWN, unsupported.changes().availability());
        var unsafeBranch = GitChanges.acquire(GitChangeOptions.defaults(directory),
                Map.of("CI_MERGE_REQUEST_TARGET_BRANCH_NAME", "../secret-sentinel"));
        assertEquals(GitChanges.Failure.INVALID_INPUT, unsafeBranch.failure());
        assertFalse(unsafeBranch.json().contains("secret-sentinel"));
    }

    @Test
    void snapshotRejectsDuplicatePathsUnknownFieldsAndInconsistentAvailability() throws Exception {
        var known = new GitChanges.Result(new SelectionContext.ChangeSet(SelectionContext.Availability.KNOWN,
                Set.of("src/changed.txt"), "a".repeat(40), "b".repeat(40)), GitChanges.Source.EXPLICIT, GitChanges.Failure.NONE).json();
        Path file = directory.resolve("snapshot.json");
        for (String invalid : new String[]{known.replace("[\"src/changed.txt\"]", "[\"src/changed.txt\",\"src/changed.txt\"]"),
                known.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                known.replace("\"schemaVersion\":1", "\"extra\":\"secret-sentinel\",\"schemaVersion\":1"),
                known.replace("\"failure\":\"NONE\"", "\"failure\":\"MISSING_REVISION\""),
                known.replace("\"source\":\"EXPLICIT\"", "\"source\":\"NOT_REQUIRED\""),
                known.replace("src/changed.txt", "../secret-sentinel")}) {
            Files.writeString(file, invalid);
            var failure = assertThrows(IllegalArgumentException.class, () -> GitChanges.read(file));
            assertFalse(failure.getMessage().contains("secret-sentinel"));
        }
        assertThrows(RuntimeException.class, () -> GitChanges.names(new byte[]{'M', 0, (byte) 0xff, 0}));
    }

    @Test
    void unrelatedAndAmbiguousAncestryRemainUnknownInsteadOfPickingAnArbitraryBase() throws Exception {
        fixture();
        var unrelated = GitChanges.acquire(options("unrelated-testdata"), Map.of());
        assertEquals(GitChanges.Failure.NO_MERGE_BASE, unrelated.failure());
        assertEquals(SelectionContext.Availability.UNKNOWN, unrelated.changes().availability());
        command("git", "-C", directory.toString(), "update-ref", "refs/heads/feature-testdata", "ambiguous-left-testdata");
        var ambiguous = GitChanges.acquire(options("ambiguous-right-testdata"), Map.of());
        assertEquals(GitChanges.Failure.NO_MERGE_BASE, ambiguous.failure());
        assertEquals(SelectionContext.Availability.UNKNOWN, ambiguous.changes().availability());
        assertTrue(ambiguous.changes().paths().isEmpty());
    }

    private static void command(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private GitChangeOptions options(String base) {
        return new GitChangeOptions(directory, base, "HEAD", true, false, "origin", 256, 30);
    }

    private String[] fixture() throws Exception {
        // All helper files stay within TempDir and the fixture's ignored build directory.
        Path launcher = directory.resolve("build/GitFixture.java");
        Files.createDirectories(launcher.getParent());
        try (var resource = getClass().getResourceAsStream("/git-fixture/GitFixture.java")) {
            assertNotNull(resource);
            Files.copy(resource, launcher);
        }
        Files.writeString(directory.resolve(".shardpilot-fixture-testdata"), "synthetic Git fixture\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), launcher.toString(), directory.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output.split("\\R");
    }
}
