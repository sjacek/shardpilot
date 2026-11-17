package io.github.sjacek.shardpilot.testing;

import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.CompiledArtifacts;
import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.SelectionContext;
import io.github.sjacek.shardpilot.SelectionDecision;
import io.github.sjacek.shardpilot.SelectionEngine;
import io.github.sjacek.shardpilot.SelectionImpact;
import io.github.sjacek.shardpilot.SelectionPolicyYaml;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/** Offline acceptance bridge using the real core. Consumer bytecode is inventoried, never loaded. */
@NullMarked
public final class ConsumerBaselineProbe {
    private ConsumerBaselineProbe() { }

    /**
     * seal: base, manifest, trusted revision, suite, then disjoint roots.
     * verify: base, manifest, trusted revision, suite, expected digest, then roots.
     * policy: baseline, policy, history, revision, TSV cases, fresh output.
     * Cases contain an ID, profile and zero or more changed repository paths.
     * The TSV is a planning matrix, not a fabricated Git diff or execution evidence.
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) throw new IllegalArgumentException("Expected seal or policy command");
        if (arguments[0].equals("seal") && arguments.length >= 6) {
            var inputs = new CompiledArtifacts.Inputs(Path.of(arguments[1]), arguments[3], arguments[4],
                    Arrays.asList(arguments).subList(5, arguments.length), List.of(), List.of(), CompiledArtifacts.Limits.defaults());
            Path manifest = Path.of(arguments[2]);
            var sealed = CompiledArtifacts.seal(inputs, manifest);
            var verified = CompiledArtifacts.verify(inputs, manifest, sealed.manifestSha256());
            System.out.println("files=" + verified.files() + " bytes=" + verified.bytes() + " sha256=" + verified.manifestSha256());
        } else if (arguments[0].equals("verify") && arguments.length >= 7) {
            var inputs = new CompiledArtifacts.Inputs(Path.of(arguments[1]), arguments[3], arguments[4],
                    Arrays.asList(arguments).subList(6, arguments.length), List.of(), List.of(), CompiledArtifacts.Limits.defaults());
            var verified = CompiledArtifacts.verify(inputs, Path.of(arguments[2]), arguments[5]);
            System.out.println("files=" + verified.files() + " bytes=" + verified.bytes() + " sha256=" + verified.manifestSha256());
        } else if (arguments[0].equals("policy") && arguments.length == 7) {
            policy(arguments);
        } else throw new IllegalArgumentException("Invalid acceptance command parameters");
    }

    private static void policy(String[] arguments) throws Exception {
        var eligible = ClassManifest.read(Path.of(arguments[1]), false);
        var policy = SelectionPolicyYaml.read(Path.of(arguments[2]));
        var history = DurationHistory.parse(Files.readString(Path.of(arguments[3])));
        Path output = Path.of(arguments[6]);
        Files.createDirectory(output);
        Files.writeString(output.resolve("policy.yaml"), SelectionPolicyYaml.format(policy));
        var ids = new TreeSet<String>();
        try (var lines = Files.lines(Path.of(arguments[5]));
             var writer = Files.newBufferedWriter(output.resolve("decisions.tsv"), StandardCharsets.UTF_8)) {
            writer.write("id\tstatus\tkind\tselected\tprotected\tbudgetExceeded\treasons\tclasses\tchangedClasses\n");
            for (String line : (Iterable<String>) lines::iterator) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 2 || !fields[0].matches("[a-z0-9][a-z0-9-]{0,127}") || !ids.add(fields[0])) {
                    throw new IllegalArgumentException("Invalid or duplicate acceptance case");
                }
                Map<String, SelectionImpact.Change> contracts = Map.of();
                if (fields[0].startsWith("contract-json-") || fields[0].startsWith("contract-yaml-")) {
                    if (fields.length != 3) throw new IllegalArgumentException("Expected one synthetic contract path");
                    var format = fields[0].startsWith("contract-json-") ? SelectionImpact.Format.JSON : SelectionImpact.Format.YAML;
                    // Explicit synthetic whole-document change: this is not a claim about historical Git blobs.
                    contracts = Map.of(fields[2], new SelectionImpact.Change(format, SelectionImpact.Failure.NONE,
                            Set.of(""), "1".repeat(64), "2".repeat(64)));
                }
                var changes = new SelectionContext.ChangeSet(SelectionContext.Availability.KNOWN,
                        new TreeSet<>(Arrays.asList(fields).subList(2, fields.length)), arguments[4], arguments[4], contracts);
                var context = new SelectionContext(fields[1], eligible, changes, history, Set.of());
                var decision = SelectionEngine.select(policy, context);
                long required = decision.families().values().stream().filter(SelectionDecision.Family::protectedTarget).count();
                writer.write(String.join("\t", fields[0], decision.status().name(), decision.kind().name(),
                        Integer.toString(decision.selected().size()), Long.toString(required), Boolean.toString(decision.budgetExceeded()),
                        decision.reasons().stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
                        String.join(",", decision.selected()),
                        decision.families().values().stream().filter(family -> family.protectedTarget()
                                && family.reasons().contains(SelectionDecision.Reason.CHANGED_TEST))
                                .flatMap(family -> family.members().stream()).sorted().collect(Collectors.joining(","))) + "\n");
                // Reviewed scenarios retain full JSON/HTML; exhaustive path sweeps retain compact rows.
                if (!fields[0].startsWith("path-")) decision.write(output.resolve(fields[0]));
            }
        }
    }
}
