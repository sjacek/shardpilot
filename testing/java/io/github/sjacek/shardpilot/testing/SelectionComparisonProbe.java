package io.github.sjacek.shardpilot.testing;

import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.GitChanges;
import io.github.sjacek.shardpilot.ProfileSelection;
import io.github.sjacek.shardpilot.SelectionContext;
import io.github.sjacek.shardpilot.SelectionDecision;
import io.github.sjacek.shardpilot.SelectionEngine;
import io.github.sjacek.shardpilot.SelectionPolicyYaml;
import io.github.sjacek.shardpilot.ShardManifests;
import io.github.sjacek.shardpilot.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/** Offline comparison entry point: evaluates retained inputs using the actual development core. */
@NullMarked
public final class SelectionComparisonProbe {
    private SelectionComparisonProbe() { }

    /** Writes planning evidence only; this is deliberately not a native execution producer. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected snapshot and fresh output directories");
        Path input = Path.of(arguments[0]), output = Path.of(arguments[1]);
        Files.createDirectory(output);
        var eligible = ClassManifest.read(input.resolve("eligibility.yaml"), false);
        var policy = SelectionPolicyYaml.read(input.resolve("policy.yaml"));
        var git = GitChanges.read(input.resolve("git.json"));
        String history = Files.readString(input.resolve("history.tsv"));
        var context = new SelectionContext("daily-testdata", eligible, git.changes(), DurationHistory.parse(history), Set.of());
        var decision = SelectionEngine.select(policy, context);
        decision.write(output.resolve("decision"));
        if (decision.status() == SelectionDecision.Status.PASSED) {
            var selected = ProfileSelection.prepare(output.resolve("snapshot"), policy, context, git,
                    "comparison-testdata", history, 2);
            ShardManifests.prepare(output.resolve("plan"), selected.selected(), TestSelection.history(history, selected.selected()), 2);
        }
    }
}
