package io.github.sjacek.shardpilot.testing;

import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.DurationHistoryRefresh;
import io.github.sjacek.shardpilot.HistoryRefreshReport;
import io.github.sjacek.shardpilot.JUnitXmlTimings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional migration oracle: invokes the real released core, without reimplementing its parser
 * or merge policy. Inputs are frozen by compare_duration_history.py. Source-derived eligibility
 * deliberately models the old script's retention boundary, not native test discovery.
 */
public final class HistoryRefreshProbe {
    private HistoryRefreshProbe() { }

    /**
     * Writes native refresh diagnostics and, on success only, a candidate history.
     * @param arguments snapshot, output, duplicate policy, pruning profile and shard count
     * @throws Exception if tooling or filesystem preparation fails
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) throw new IllegalArgumentException("Expected five migration arguments");
        Path snapshot = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        Files.createDirectories(output);
        Path candidate = output.resolve("durations.tsv");
        Files.deleteIfExists(candidate);
        var options = new DurationHistoryRefresh.Options(.1, 1, false,
                DurationHistoryRefresh.Duplicates.valueOf(arguments[2]), 0, Integer.parseInt(arguments[4]));
        var eligibility = arguments[3].equals("disabled") ? DurationHistoryRefresh.Eligibility.disabled()
                : new DurationHistoryRefresh.Eligibility(DurationHistoryRefresh.Source.BASELINE,
                        Set.copyOf(Files.readAllLines(snapshot.resolve("eligible.txt"))));
        Map<String, Double> previous;
        try { previous = DurationHistory.parse(Files.readString(snapshot.resolve("previous.tsv"))); }
        catch (IllegalArgumentException failure) {
            Files.writeString(output.resolve("durations.json"),
                    "{\"status\":\"FAILED\",\"reason\":\"INVALID_HISTORY\"}\n");
            System.exit(1);
            return;
        }
        List<Path> roots = Files.readAllLines(snapshot.resolve("report-roots.txt")).stream().map(snapshot::resolve).toList();
        List<JUnitXmlTimings.Suite> suites;
        boolean incomplete = false;
        try { suites = JUnitXmlTimings.read(roots); }
        catch (JUnitXmlTimings.ReadFailure failure) { suites = failure.partial(); incomplete = true; }
        // Only suite-level flags leave this process; never serialize testcase names or XML payloads.
        var metadata = new ArrayList<String>();
        for (var suite : suites) {
            String name = Base64.getUrlEncoder().withoutPadding().encodeToString(suite.name().getBytes(StandardCharsets.UTF_8));
            metadata.add(String.join("\t", name, Double.toString(suite.seconds()),
                    Boolean.toString(suite.executed()), Boolean.toString(suite.failed()),
                    Boolean.toString(suite.retried()), Boolean.toString(suite.ambiguous())));
        }
        Files.write(output.resolve("suites.tsv"), metadata, StandardCharsets.UTF_8);
        var result = DurationHistoryRefresh.refresh(previous, suites, eligibility, options);
        var report = incomplete ? result.report().failedRead(HistoryRefreshReport.Reason.INVALID_XML, "Incomplete XML input") : result.report();
        Files.writeString(output.resolve("durations.json"), report.json(), StandardCharsets.UTF_8);
        if (result.passed() && !incomplete) Files.writeString(candidate, DurationHistory.format(result.history(), 1), StandardCharsets.UTF_8);
        if (!result.passed() || incomplete) System.exit(1);
    }
}
