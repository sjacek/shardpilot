package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Synthetic, reproducible retained artifacts; never performs Git, compilation or native execution. */
final class ExecutionSelectionFixture {
    static final String REVISION = "a".repeat(40);
    static final String PROFILE = "smoke-testdata";
    static final String SUITE = "profile-smoke-testdata";

    private ExecutionSelectionFixture() { }

    /** Developer-only fixture regeneration; test assertions always write to temporary directories. */
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        prepare(target);
        bindNodes(target, Path.of("docs/examples/execution-evidence-testdata"));
        var result = ExecutionEvidence.writeReports(evidence(target, true), target.resolve("report.json"), target.resolve("report.html"));
        if (!result.passed()) throw new AssertionError(result.json());
    }

    static void prepare(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("policy.yaml"), """
                schemaVersion: 1
                profiles:
                  smoke-testdata:
                    mode: smoke
                    mandatory: {classes: [example.AlphaIT, example.BetaIT]}
                """);
        ClassManifest.write(root.resolve("baseline.yaml"), Set.of("example.AlphaIT", "example.AlphaIT$Nested", "example.BetaIT", "example.UnrelatedIT"));
        Files.writeString(root.resolve("history.tsv"), "example.AlphaIT\t10\nexample.BetaIT\t5\nexample.UnrelatedIT\t100\n");
        Files.createDirectories(root.resolve("compiled/classes"));
        Files.writeString(root.resolve("compiled/classes/synthetic-testdata.txt"), "Synthetic compiled-input fixture, not executable bytecode.\n");
        CompiledArtifacts.seal(compiled(root).inputs(), root.resolve("compiled-manifest.json"));
        snapshot(root);
        ExecutionSelection.write(inputs(root, true), root.resolve("selection.json"));
    }

    static void snapshot(Path root) throws Exception {
        var policy = SelectionPolicyYaml.read(root.resolve("policy.yaml"));
        var eligible = ClassManifest.read(root.resolve("baseline.yaml"), false);
        String history = Files.readString(root.resolve("history.tsv"));
        var git = GitChanges.notRequired();
        ProfileSelection.prepare(root.resolve("snapshot"), policy,
                new SelectionContext(PROFILE, eligible, git.changes(), DurationHistory.parse(history), Set.of()),
                git, SUITE, history, 2);
    }

    static ExecutionSelection.Inputs inputs(Path root, boolean withCompiled) {
        return new ExecutionSelection.Inputs(root.resolve("snapshot"), root.resolve("policy.yaml"), root.resolve("baseline.yaml"),
                root.resolve("history.tsv"), null, PROFILE, REVISION, SUITE, 2, withCompiled ? compiled(root) : null);
    }

    static ExecutionSelection.Compiled compiled(Path root) {
        return new ExecutionSelection.Compiled(new CompiledArtifacts.Inputs(root.resolve("compiled"), REVISION, SUITE,
                List.of("classes"), List.of("classes"), List.of("classes/synthetic-testdata.txt"), CompiledArtifacts.Limits.defaults()),
                root.resolve("compiled-manifest.json"), "");
    }

    static ExecutionEvidence.Inputs evidence(Path root, boolean withCompiled) {
        return new ExecutionEvidence.Inputs(root.resolve("expected.json"), root.resolve("baseline.yaml"), root.resolve("selection.json"),
                root.resolve("nodes"), ExecutionEvidence.Limits.defaults(), withCompiled ? compiled(root) : null);
    }

    /** Retain the old v1 process/XML protocol, replacing only independently pinned selection/plan bytes. */
    static void bindNodes(Path root, Path legacy) throws Exception {
        var expected = object(EvidenceJson.parse(Files.readString(legacy.resolve("expected.json"))));
        object(expected.get("cohort")).put("suite", SUITE);
        var selection = object(expected.get("selection"));
        selection.put("mode", "explicit"); selection.put("sha256", sha(root.resolve("selection.json")));
        selection.put("eligibilitySha256", sha(root.resolve("baseline.yaml")));
        var eligible = ClassManifest.read(root.resolve("baseline.yaml"), false);
        var selected = ProfileSelection.read(root.resolve("snapshot"), SelectionPolicyYaml.read(root.resolve("policy.yaml")), PROFILE,
                eligible, Set.of(), SUITE, Files.readString(root.resolve("history.tsv")), 2);
        ShardManifests.prepare(root.resolve("plan"), selected.selected(), TestSelection.history(Files.readString(root.resolve("history.tsv")), selected.selected()), 2);
        for (int index = 1; index <= 2; index++) {
            Path node = root.resolve("nodes/node-" + index); Files.createDirectories(node.resolve("reports"));
            Files.copy(root.resolve("plan/planned-" + index + ".yaml"), node.resolve("plan.yaml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var evidence = object(EvidenceJson.parse(Files.readString(legacy.resolve("node-" + index + "/evidence.json"))));
            object(evidence.get("cohort")).put("suite", SUITE);
            object(evidence.get("selection")).putAll(selection);
            object(evidence.get("selection")).put("planSha256", sha(node.resolve("plan.yaml")));
            object(((List<?>) expected.get("expectedNodes")).get(index - 1)).put("planSha256", sha(node.resolve("plan.yaml")));
            Path xml = legacy.resolve("node-" + index + "/reports/TEST-example." + (index == 1 ? "AlphaIT" : "BetaIT") + ".xml");
            Files.copy(xml, node.resolve("reports/" + xml.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(node.resolve("evidence.json"), EvidenceJson.write(evidence));
        }
        Files.writeString(root.resolve("expected.json"), EvidenceJson.write(expected));
    }

    static String sha(Path file) throws Exception { return EvidenceFiles.digest(Files.readAllBytes(file)); }

    /** Test-only mutable JSON object; the production parser validates all actual boundary shapes. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new AssertionError("Expected fixture JSON object");
        return (Map<String, Object>) raw;
    }
}
