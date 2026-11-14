package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/** Shared adapter boundary: freeze automatic decisions once and validate them without reacquiring a diff. */
@NullMarked
public final class ProfileSelection {
    private static final List<String> DECISION_FILES = List.of("git.json", "decision.json", "decision.html", "eligible.yaml", "selected.yaml", "decision.id");

    private ProfileSelection() { }

    /**
     * Derives a safe default namespace, including for a profile named full.
     * @param profile configured profile ID
     * @return profile-prefixed suite, distinct from legacy full-suite output
     */
    public static String defaultSuite(String profile) {
        SelectionPolicy.identifier(profile);
        return "profile-" + profile;
    }

    /**
     * Generates an immutable source manifest and legacy-compatible cohort sidecars from one decision.
     * @param directory generated selection output, separate from maintained inputs
     * @param policy parsed common policy
     * @param context frozen eligibility, changes, profile, timings and explicit requests
     * @param git acquisition evidence matching context.changes
     * @param suite selected-suite namespace
     * @param history exact history bytes used by all nodes
     * @param count partition count
     * @return validated selection for the existing native ownership boundary
     * @throws IOException if generated evidence cannot be written
     */
    public static TestSelection prepare(Path directory, SelectionPolicy policy, SelectionContext context,
                                        GitChanges.Result git, String suite, String history, int count) throws IOException {
        TestSelection.validateSuite(suite);
        ShardPlan.validateShard(count, 1);
        if (!context.changes().equals(git.changes()) || !context.history().equals(DurationHistory.parse(history))) {
            throw new IllegalArgumentException("Profile preparation inputs disagree");
        }
        // Invalidate executable outer artifacts before evaluation as well as before report writes.
        invalidate(directory);
        try {
            var decision = SelectionEngine.select(policy, context);
            var evidence = directory.resolve("decision");
            decision.write(evidence);
            Files.writeString(evidence.resolve("git.json"), git.json(), StandardCharsets.UTF_8);
            if (decision.status() != SelectionDecision.Status.PASSED) {
                throw new IllegalArgumentException("Profile selection failed; inspect decision/decision.json");
            }
            Path source = evidence.resolve("selected.yaml");
            // Legacy cohort identity hashes the exact source bytes. These comments bind policy/Git
            // provenance without changing class-manifest v1 or any existing explicit selection identity.
            Files.writeString(source, header(decision, git) + Files.readString(source), StandardCharsets.UTF_8);
            var selection = TestSelection.read(source, context.eligible(), suite);
            selection.write(directory, history, count);
            return selection;
        } catch (IllegalArgumentException failure) {
            TestSelection.writeFailure(directory, "Profile selection failed; inspect decision evidence");
            throw failure;
        }
    }

    /**
     * Revalidates a downloaded snapshot against current policy, eligibility, history and native count.
     * The frozen changes are used as-is; no diff, fetch or producer is run and no source artifact is changed.
     * @param source downloaded complete selection directory
     * @param policy current policy
     * @param profile requested profile
     * @param eligible current independently discovered full eligibility
     * @param explicit requested names for explicit profiles
     * @param suite selected-suite identity
     * @param history current full timing input
     * @param count total partitions
     * @return validated existing source selection
     * @throws IOException if retained evidence cannot be read
     */
    public static TestSelection read(Path source, SelectionPolicy policy, String profile, Set<String> eligible,
                                     Set<String> explicit, String suite, String history, int count) throws IOException {
        Path evidence = source.resolve("decision");
        var git = GitChanges.read(evidence.resolve("git.json"));
        var context = new SelectionContext(profile, eligible, git.changes(), DurationHistory.parse(history), explicit);
        var decision = SelectionEngine.select(policy, context);
        // Compare the complete recomputed evidence, not just S: policy/budget/provenance changes can
        // leave the same class list. Validation is local and never consults a mutable base branch.
        if (decision.status() != SelectionDecision.Status.PASSED
                || contentDiffers(evidence.resolve("decision.id"), decision.identity() + "\n")
                || contentDiffers(evidence.resolve("decision.json"), decision.json())
                || contentDiffers(evidence.resolve("decision.html"), decision.html())
                || !ClassManifest.read(evidence.resolve("eligible.yaml"), false).equals(eligible)
                || !ClassManifest.read(source.resolve("eligible.yaml"), false).equals(eligible)) {
            throw new IllegalArgumentException("Frozen profile inputs or decision evidence differ");
        }
        Path manifest = evidence.resolve("selected.yaml");
        byte[] prefix = header(decision, git).getBytes(StandardCharsets.UTF_8);
        try (var input = Files.newInputStream(manifest)) {
            if (!java.util.Arrays.equals(prefix, input.readNBytes(prefix.length))) throw new IllegalArgumentException("Frozen profile provenance differs");
        }
        var selection = TestSelection.read(manifest, eligible, suite);
        // Legacy identity additionally binds exact history bytes, suite and shard count. Semantically
        // equal timing maps therefore cannot accidentally join two differently prepared cohorts.
        if (!selection.selected().equals(decision.selected())
                || !ClassManifest.read(source.resolve("selected.yaml"), false).equals(decision.selected())
                || contentDiffers(source.resolve("selection.id"), selection.identity(history, count))) {
            throw new IllegalArgumentException("Frozen profile cohort differs");
        }
        return selection;
    }

    /**
     * Validates a snapshot, checks its local checkout and copies only its owned artifacts to fresh outputs.
     * @param source read-only snapshot, outside the generated output
     * @param output runtime selection directory
     * @param policy current common policy
     * @param profile requested profile
     * @param eligible current full eligibility
     * @param explicit explicit request names
     * @param suite selected-suite identity
     * @param history full timing bytes
     * @param count total partitions
     * @param checkout worker checkout used only for local HEAD/cleanliness validation
     * @return restored immutable selection
     * @throws IOException if artifacts cannot be copied
     */
    public static TestSelection restore(Path source, Path output, SelectionPolicy policy, String profile,
                                        Set<String> eligible, Set<String> explicit, String suite, String history,
                                        int count, Path checkout) throws IOException {
        protectSnapshot(source, output);
        // Directory overlap checks cannot detect hard-linked files in otherwise separate trees.
        var names = new java.util.ArrayList<>(List.of("selected.yaml", "eligible.yaml", "selection.id", "selection.json", "selection.html"));
        names.addAll(DECISION_FILES.stream().map(name -> "decision/" + name).toList());
        Path[] targets = names.stream().map(output::resolve).toArray(Path[]::new);
        for (String name : names) TestSelection.protectInput(source.resolve(name), targets);
        invalidate(output);
        var selection = read(source, policy, profile, eligible, explicit, suite, history, count);
        var git = GitChanges.read(source.resolve("decision/git.json"));
        if (!git.changes().headRevision().isEmpty()) {
            // UNKNOWN impact already selected the safe full/fail outcome; only KNOWN committed impact
            // requires cleanliness. A retained HEAD still prevents reuse on another revision.
            GitChanges.verifyCheckout(checkout, git.changes().headRevision(), git.changes().availability() == SelectionContext.Availability.KNOWN);
        }
        Files.createDirectories(output.resolve("decision"));
        // Copy only owned evidence. Extra files in a downloaded artifact are neither executed nor restored.
        for (String name : DECISION_FILES) Files.copy(source.resolve("decision").resolve(name), output.resolve("decision").resolve(name), StandardCopyOption.REPLACE_EXISTING);
        selection.write(output, history, count);
        return selection;
    }

    /**
     * Removes only executable outer snapshots before planning; retained native evidence is untouched.
     * @param directory generated selection directory
     * @throws IOException if stale executable files cannot be removed
     */
    public static void invalidate(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve("selected.yaml"));
        Files.deleteIfExists(directory.resolve("selection.id"));
    }

    /**
     * Checks directory overlap through existing parent symlinks before invalidation or copying.
     * @param source downloaded snapshot directory
     * @param output generated directory, which may not exist yet
     * @throws IOException if canonical parents cannot be resolved
     */
    public static void protectSnapshot(Path source, Path output) throws IOException {
        Path input = canonical(source);
        Path target = canonical(output);
        if (target.startsWith(input) || input.startsWith(target)) {
            throw new IllegalArgumentException("Keep the frozen selection outside generated selection outputs");
        }
    }

    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    private static String header(SelectionDecision decision, GitChanges.Result git) {
        return "# ShardPilot decision: " + decision.identity() + "\n# ShardPilot Git inputs: " + digest(git.json()) + "\n";
    }

    private static boolean contentDiffers(Path file, String expected) throws IOException {
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        try (var input = Files.newInputStream(file)) {
            return !java.util.Arrays.equals(bytes, input.readNBytes(bytes.length + 1));
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by Java", impossible); }
    }
}
