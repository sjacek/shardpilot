package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.EvidenceFiles.problem;
import static io.github.sjacek.shardpilot.ExecutionEvidence.Reason.*;

/**
 * Artifact-only bridge from a replayed C5 decision to C2's existing selection digest. Version 2
 * binds decision inputs even when their selected families coincide. No Git or producer is run.
 * Only canonical selection JSON gains a new version: expected-cohort, per-node evidence and C3
 * manifests retain their v1 protocols. The trusted planning channel must pin the resulting digest;
 * producer claims alone cannot establish which decision the aggregate job intended to verify.
 */
@NullMarked
public final class ExecutionSelection {
    private static final int JSON_LIMIT = 1_048_576;
    private static final Set<String> PROVENANCE_FIELDS = Set.of("revision", "suite", "shardCount", "profile", "profileMode",
            "engineVersion", "decisionIdentity", "policySha256", "gitSha256", "historySha256", "eligibilitySha256",
            "sourceManifestSha256", "cohortSha256", "compiledArtifactsSha256", "planSha256");

    private ExecutionSelection() { }

    /**
     * Optional C3 verification uses independent roots and identity, never a policy read from the manifest.
     * @param inputs trusted C3 identity, paths and resource limits
     * @param manifest retained v1 C3 manifest outside its inventoried trees
     * @param expectedManifestSha256 independent digest, or empty when the artifact channel is trusted
     */
    public record Compiled(CompiledArtifacts.Inputs inputs, Path manifest, String expectedManifestSha256) {
        /** Rejects malformed pins before any filesystem access. */
        public Compiled {
            if (!expectedManifestSha256.isEmpty() && !expectedManifestSha256.matches("[a-f0-9]{64}")) throw problem(COMPILED_ARTIFACT_MISMATCH);
        }

        /**
         * Producer calls use the optional independently configured pin. Aggregate calls must use the
         * manifest digest already bound by selection v2, and reject a conflicting independent pin.
         * Both paths verify actual files; retaining a matching manifest alone is insufficient.
         */
        String verify(String revision, String suite, @Nullable String pinned) {
            if (!inputs.revision().equals(revision) || !inputs.suite().equals(suite)
                    || pinned != null && !expectedManifestSha256.isEmpty() && !pinned.equals(expectedManifestSha256)) throw problem(COMPILED_ARTIFACT_MISMATCH);
            try {
                return CompiledArtifacts.verify(inputs, manifest, pinned == null ? expectedManifestSha256 : pinned).manifestSha256();
            } catch (IllegalArgumentException failure) { throw problem(COMPILED_ARTIFACT_MISMATCH); }
        }

        /**
         * Validate all output aliases before either writer invalidates an old report. A path outside
         * the roots can still be a hard link to an input, so existing outputs require a bounded scan.
         * Ownership failures leave inputs and prior outputs intact; callers must require the gate outcome.
         */
        void protect(Path... outputs) throws IOException {
            TestSelection.protectInput(manifest, outputs);
            for (Path output : outputs) {
                Path target = EvidenceFiles.canonical(output);
                EvidenceFiles.safe(output);
                if (Files.exists(output) && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Outputs must be regular files");
                long visited = 0;
                for (String root : inputs.roots()) {
                    Path tree = inputs.directory().resolve(root);
                    EvidenceFiles.safe(tree);
                    Path source = EvidenceFiles.canonical(tree);
                    if (target.startsWith(source) || source.startsWith(target)) throw new IllegalArgumentException("Keep reports outside compiled input trees");
                    if (Files.exists(output) && Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) {
                        try (var paths = Files.walk(tree, inputs.limits().maxDepth() + 1)) {
                            var iterator = paths.iterator();
                            while (iterator.hasNext()) {
                                if (++visited > inputs.limits().maxEntries()) throw problem(RESOURCE_LIMIT);
                                Path file = iterator.next();
                                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(output, file)) throw new IllegalArgumentException("Reports must not alias compiled inputs");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Maintained inputs and a complete frozen C5 selection, independent of downloaded C2 node claims.
     * @param snapshotDirectory previously prepared C5 selection directory
     * @param policyFile current reviewed policy
     * @param eligibilityFile independently retained complete native class manifest
     * @param historyFile exact original timing bytes, or null for empty history
     * @param explicitManifestFile original exact requests for an explicit profile, otherwise null
     * @param profile selected profile ID
     * @param revision trusted CI source revision; also checked against a retained Git HEAD when present
     * @param suite selected-suite identity
     * @param shardCount common number of nodes
     * @param compiled optional independent C3 verification
     */
    public record Inputs(Path snapshotDirectory, Path policyFile, Path eligibilityFile, @Nullable Path historyFile,
                         @Nullable Path explicitManifestFile, String profile, String revision, String suite,
                         int shardCount, @Nullable Compiled compiled) {
        /** Validate identities without reading mutable refs or inventing a revision for a full/smoke profile. */
        public Inputs {
            SelectionPolicy.identifier(profile);
            TestSelection.validateSuite(suite);
            ShardPlan.validateShard(shardCount, 1);
            if (!revision.matches("[a-f0-9]{40}|[a-f0-9]{64}") || shardCount > 1024 || suite.length() > 128) throw problem(SELECTION_PROVENANCE_MISMATCH);
        }
    }

    /**
     * Replays frozen C5 inputs and writes canonical selection v2, compatible with C2 v1 request/node hashes.
     * The output must be retained through an authenticated CI channel and its digest supplied independently.
     * Ownership is established before invalidating the previous output. Once replay begins, corrupt or
     * missing evidence cannot leave an older executable document available under the owned output path.
     * @param inputs reviewed policy, timing, eligibility, source revision and optional C3 inputs
     * @param output generated canonical selection JSON outside retained inputs
     * @return SHA-256 of the exact generated bytes (no trailing newline)
     * @throws IOException if the owned output cannot be replaced
     */
    public static String write(Inputs inputs, Path output) throws IOException {
        protect(inputs, output);
        Files.deleteIfExists(output);
        String result = create(inputs);
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JSON_LIMIT) throw problem(RESOURCE_LIMIT);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, bytes);
        return EvidenceFiles.digest(bytes);
    }

    private static String create(Inputs inputs) {
        try {
            // Bound reads and reject unsafe paths before ProfileSelection reads its fixed owned files. Old snapshots
            // remain valid: this operation adds a new output and never changes their manifest protocol.
            Path source = inputs.snapshotDirectory();
            for (String name : List.of("selected.yaml", "eligible.yaml", "selection.id", "selection.json", "selection.html", "decision/selected.yaml", "decision/eligible.yaml",
                    "decision/decision.id", "decision/decision.json", "decision/decision.html")) EvidenceFiles.read(source.resolve(name), JSON_LIMIT);
            byte[] gitBytes = EvidenceFiles.read(source.resolve("decision/git.json"), 8_388_608);
            var git = GitChanges.read(source.resolve("decision/git.json"));
            // Do not resolve mutable refs here. Full/smoke snapshots can legitimately lack Git metadata,
            // but a retained nonempty HEAD must agree with the separately supplied CI revision.
            if (!git.changes().headRevision().isEmpty() && !git.changes().headRevision().equals(inputs.revision())) throw problem(SELECTION_PROVENANCE_MISMATCH);
            var policy = SelectionPolicyYaml.parse(EvidenceFiles.text(EvidenceFiles.read(inputs.policyFile(), JSON_LIMIT)));
            byte[] baseline = EvidenceFiles.read(inputs.eligibilityFile(), JSON_LIMIT);
            Set<String> eligible = ClassManifest.parse(EvidenceFiles.text(baseline), false);
            String history = inputs.historyFile() == null ? "" : EvidenceFiles.text(EvidenceFiles.read(inputs.historyFile(), 8_388_608));
            Set<String> explicit = inputs.explicitManifestFile() == null ? Set.of()
                    : ClassManifest.parse(EvidenceFiles.text(EvidenceFiles.read(inputs.explicitManifestFile(), JSON_LIMIT)), false);
            var selected = ProfileSelection.read(source, policy, inputs.profile(), eligible, explicit, inputs.suite(), history, inputs.shardCount());
            var decision = SelectionEngine.select(policy, new SelectionContext(inputs.profile(), eligible, git.changes(), DurationHistory.parse(history), explicit));
            var profileMode = decision.mode();
            if (profileMode == null) throw problem(SELECTION_PROVENANCE_MISMATCH);
            var provenance = new TreeMap<String, @Nullable Object>();
            provenance.put("revision", inputs.revision()); provenance.put("suite", inputs.suite()); provenance.put("shardCount", inputs.shardCount());
            provenance.put("profile", inputs.profile()); provenance.put("profileMode", profileMode.name()); provenance.put("engineVersion", 1);
            provenance.put("decisionIdentity", decision.identity()); provenance.put("policySha256", decision.policySha256());
            provenance.put("gitSha256", EvidenceFiles.digest(gitBytes));
            provenance.put("historySha256", EvidenceFiles.digest(history.getBytes(StandardCharsets.UTF_8)));
            provenance.put("eligibilitySha256", EvidenceFiles.digest(baseline));
            provenance.put("sourceManifestSha256", selected.manifestSha256());
            provenance.put("cohortSha256", selected.identity(history, inputs.shardCount()));
            // Hash the exact existing v1 YAML writer, including eligible nested classes. C2 still
            // reasons about outer families, but cannot substitute another valid partition of S.
            // Balancing uses only selected-family timings; historySha256 above binds the full raw input.
            var plans = new TreeMap<String, String>();
            var plan = ShardPlan.create(TestSelection.history(history, selected.selected()), inputs.shardCount());
            for (int index = 1; index <= inputs.shardCount(); index++) {
                int node = index;
                var names = new TreeSet<String>();
                selected.selected().stream().filter(name -> plan.shardFor(name) == node).forEach(names::add);
                plans.put(Integer.toString(index), EvidenceFiles.digest(ClassManifest.format(names).getBytes(StandardCharsets.UTF_8)));
            }
            provenance.put("planSha256", plans);
            provenance.put("compiledArtifactsSha256", inputs.compiled() == null ? null : inputs.compiled().verify(inputs.revision(), inputs.suite(), null));
            var document = new TreeMap<String, Object>();
            document.put("schemaVersion", 2); document.put("provenance", provenance);
            boolean full = profileMode == SelectionPolicy.Mode.FULL;
            // An affected profile's full fallback remains an explicit profile result, not a claim that
            // the independently configured full-suite gate ran. profileMode retains that distinction.
            document.put("mode", full ? "full" : "explicit");
            if (!full) document.put("families", families(selected.selected()));
            return EvidenceJson.write(document);
        } catch (EvidenceFiles.Problem failure) { throw failure; }
        catch (IOException | IllegalArgumentException failure) { throw problem(SELECTION_PROVENANCE_MISMATCH); }
    }

    record Validated(Set<String> selected, Map<String, @Nullable Object> provenance) { }

    /**
     * C2 validates the independently pinned document, not producer-supplied replacements of its fields.
     * Shape, expected identity, every plan and exact canonical bytes are checked before optional C3 I/O.
     * Provenance is returned only after all checks pass, so a report cannot present a partial binding as verified.
     */
    static Validated validate(Map<String, @Nullable Object> root, byte[] bytes, Set<String> eligible, String revision,
                              String suite, int count, String baselineHash, Map<String, String> expectedPlans,
                              int maxFamilies, @Nullable Compiled compiled) {
        boolean full = "full".equals(root.get("mode"));
        if (!root.keySet().equals(full ? Set.of("schemaVersion", "mode", "provenance") : Set.of("schemaVersion", "mode", "provenance", "families"))) throw problem(INVALID_EVIDENCE);
        var metadata = fields(root.get("provenance"));
        if (!metadata.keySet().equals(PROVENANCE_FIELDS)) throw problem(INVALID_EVIDENCE);
        if (!revision.equals(metadata.get("revision")) || !suite.equals(metadata.get("suite"))
                || !BigDecimal.valueOf(count).equals(metadata.get("shardCount")) || !baselineHash.equals(metadata.get("eligibilitySha256"))) throw problem(SELECTION_PROVENANCE_MISMATCH);
        TestSelection.validateSuite(suite);
        if (suite.length() > 128) throw problem(INVALID_EVIDENCE);
        SelectionPolicy.identifier(string(metadata.get("profile")));
        String mode = string(metadata.get("profileMode"));
        if (!Set.of("FULL", "SMOKE", "AFFECTED", "EXPLICIT").contains(mode) || full != mode.equals("FULL")
                || !BigDecimal.ONE.equals(metadata.get("engineVersion"))) throw problem(INVALID_EVIDENCE);
        for (String key : List.of("decisionIdentity", "policySha256", "gitSha256", "historySha256", "eligibilitySha256", "sourceManifestSha256", "cohortSha256")) hash(metadata.get(key));
        var plans = fields(metadata.get("planSha256"));
        plans.values().forEach(ExecutionSelection::hash);
        if (!plans.equals(expectedPlans)) throw problem(SELECTION_PROVENANCE_MISMATCH);
        Set<String> selected = eligible;
        if (!full) {
            var names = new TreeSet<String>();
            if (!(root.get("families") instanceof List<?> list) || list.isEmpty() || list.size() > 100_000) throw problem(SELECTION_MISMATCH);
            if (list.size() > maxFamilies) throw problem(RESOURCE_LIMIT);
            for (Object value : list) {
                String name = string(value);
                if (DurationHistory.invalidClassName(name) || name.contains("$") || !names.add(name)) throw problem(SELECTION_MISMATCH);
            }
            if (!eligible.containsAll(names)) throw problem(SELECTION_MISMATCH);
            selected = names;
        }
        metadata.put("planSha256", plans);
        var canonical = new TreeMap<>(root); canonical.put("provenance", metadata);
        if (!full) canonical.put("families", selected);
        if (!EvidenceFiles.text(bytes).equals(EvidenceJson.write(canonical))) throw problem(SELECTION_MISMATCH);
        Object compiledHash = metadata.get("compiledArtifactsSha256");
        if (compiledHash == null) {
            if (compiled != null) throw problem(COMPILED_ARTIFACT_MISMATCH);
        } else {
            if (compiled == null) throw problem(COMPILED_ARTIFACT_MISMATCH);
            compiled.verify(revision, suite, hash(compiledHash));
        }
        return new Validated(selected, metadata);
    }

    private static void protect(Inputs inputs, Path output) throws IOException {
        EvidenceFiles.safe(output);
        ProfileSelection.protectSnapshot(inputs.snapshotDirectory(), output);
        for (Path input : List.of(inputs.policyFile(), inputs.eligibilityFile())) TestSelection.protectInput(input, output);
        if (inputs.historyFile() != null) TestSelection.protectInput(inputs.historyFile(), output);
        if (inputs.explicitManifestFile() != null) TestSelection.protectInput(inputs.explicitManifestFile(), output);
        // Check existing hard links to any owned snapshot file without traversing arbitrary extra files.
        for (String name : List.of("selected.yaml", "eligible.yaml", "selection.id", "selection.json", "selection.html",
                "decision/git.json", "decision/selected.yaml", "decision/eligible.yaml", "decision/decision.json", "decision/decision.html", "decision/decision.id")) TestSelection.protectInput(inputs.snapshotDirectory().resolve(name), output);
        if (Files.exists(output) && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Execution selection output must be a regular file");
        if (inputs.compiled() != null) inputs.compiled().protect(output);
    }

    private static Set<String> families(Set<String> classes) {
        var names = new TreeSet<String>(); classes.forEach(name -> names.add(DurationHistory.family(name))); return names;
    }

    private static Map<String, @Nullable Object> fields(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw problem(INVALID_EVIDENCE);
        var result = new TreeMap<String, @Nullable Object>();
        raw.forEach((key, entry) -> result.put(string(key), entry));
        return result;
    }

    private static String string(@Nullable Object value) {
        if (value instanceof String text) return text;
        throw problem(INVALID_EVIDENCE);
    }

    private static String hash(@Nullable Object value) {
        String result = string(value);
        if (!result.matches("[a-f0-9]{64}")) throw problem(INVALID_EVIDENCE);
        return result;
    }
}
