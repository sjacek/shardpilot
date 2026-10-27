package io.github.sjacek.shardpilot;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable refresh evidence; JSON never includes XML payloads, testcase names or timings.
 * Null counts/loads mean an unfinished phase, whereas zero means that phase found no matching
 * records. Details are supplied by trusted adapter diagnostics; arbitrary parser messages must
 * never be passed to this boundary. Lifecycle output deliberately excludes even those details.
 * @param status successful or failed aggregation
 * @param reason failure code, absent on success
 * @param detail safe diagnostic, never an arbitrary parser message
 * @param counts record/suite counts known at the failure or completion point
 * @param eligible number of eligible families, null when disabled or unavailable
 * @param pruning resolved eligibility source
 * @param coverage measured fraction of eligible families, null when disabled or unknown
 * @param shardCount partition size
 * @param previousLoad old recorded seconds per shard, null if unknown
 * @param refreshedLoad new recorded seconds per shard, null if unknown
 * @param diagnostics additional exclusion counts keyed by stable reason codes
 */
public record HistoryRefreshReport(Status status, Reason reason, String detail, Counts counts,
                                   Integer eligible, DurationHistoryRefresh.Source pruning, Double coverage,
                                   int shardCount, List<Double> previousLoad, List<Double> refreshedLoad,
                                   Map<Reason, Integer> diagnostics) {
    /** Outcome retained even when the build-tool task fails. */
    public enum Status {
        /** Candidate history is safe to publish as a build artifact. */
        PASSED,
        /** No candidate history may be published. */
        FAILED
    }

    /** Stable, transport-independent diagnostics; exception messages from parsers are not exposed. */
    public enum Reason {
        /** No family provided an accepted sample. */
        NO_MEASUREMENTS,
        /** The configured eligibility source contains no families. */
        EMPTY_ELIGIBILITY,
        /** Accepted families do not meet the configured fraction. */
        MINIMUM_COVERAGE,
        /** A raw suite name repeated under FAIL policy. */
        DUPLICATE_SUITE,
        /** Different suite times cover the same family member. */
        OVERLAPPING_FAMILY,
        /** XML could not be read securely and completely. */
        INVALID_XML,
        /** Eligibility input is missing, unreadable or malformed. */
        INVALID_BASELINE,
        /** Previous TSV or its recorded plan is invalid. */
        INVALID_HISTORY,
        /** Measurements contain inconsistent values or overflow. */
        INVALID_DATA,
        /** Otherwise valid members belong to an excluded partial family. */
        INCOMPLETE_FAMILY
    }

    /**
     * Record and suite units are separate; null means the corresponding phase was not completed.
     * @param measured new complete family records
     * @param measuredSuites accepted source suites after duplicate policy
     * @param kept old records retained without a new family sample
     * @param dropped old records removed by eligibility pruning
     * @param replaced old records replaced by new family samples
     * @param foreign suites outside eligibility
     * @param skipped suites without executed testcases
     * @param retried suites containing retry markers
     * @param ambiguous suites with repeated identities or incomplete families
     * @param failed non-retried failures excluded by policy
     */
    public record Counts(Integer measured, Integer measuredSuites, Integer kept, Integer dropped, Integer replaced,
                         Integer foreign, Integer skipped, Integer retried, Integer ambiguous, Integer failed) { }

    /** Takes immutable copies of calculated load/diagnostic evidence. */
    public HistoryRefreshReport {
        previousLoad = previousLoad == null ? null : List.copyOf(previousLoad);
        refreshedLoad = refreshedLoad == null ? null : List.copyOf(refreshedLoad);
        diagnostics = Map.copyOf(diagnostics);
    }

    /**
     * Deterministic JSON with explicitly unknown values represented by null.
     * @return deterministic JSON with explicitly unknown values represented by null
     */
    public String json() {
        return "{\"status\":" + quote(status.name())
                + (reason == null ? "" : ",\"reason\":" + quote(reason.name()))
                + (detail == null ? "" : ",\"detail\":" + quote(detail))
                + ",\"measured\":" + counts.measured() + ",\"measuredSuites\":" + counts.measuredSuites()
                + ",\"kept\":" + counts.kept() + ",\"dropped\":" + counts.dropped() + ",\"replaced\":" + counts.replaced()
                + ",\"foreign\":" + counts.foreign()
                + ",\"ignored\":{\"skipped\":" + counts.skipped() + ",\"retried\":" + counts.retried()
                + ",\"ambiguous\":" + counts.ambiguous() + ",\"failed\":" + counts.failed() + "}"
                + ",\"eligible\":" + eligible + ",\"pruning\":" + quote(pruning.label()) + ",\"coverage\":" + coverage
                + ",\"shardCount\":" + shardCount
                + ",\"recordedLoad\":{\"previous\":" + previousLoad + ",\"refreshed\":" + refreshedLoad + "}"
                + ",\"imbalance\":{\"previous\":" + imbalance(previousLoad)
                + ",\"refreshed\":" + imbalance(refreshedLoad) + "}"
                + ",\"diagnostics\":" + diagnosticJson() + "}\n";
    }

    /**
     * One concise lifecycle line without filenames, testcase identities or diagnostic payloads.
     * @return one concise lifecycle line without filenames, testcase identities or diagnostic payloads
     */
    public String lifecycle() {
        return "ShardPilot history " + status + (reason == null ? "" : " (" + reason + ")")
                + ": measured=" + counts.measured() + ", kept=" + counts.kept() + ", dropped=" + counts.dropped()
                + ", foreign=" + counts.foreign() + ", coverage=" + coverage
                + ", imbalance=" + imbalance(previousLoad) + " -> " + imbalance(refreshedLoad);
    }

    /**
     * Retains collected partial counters when XML reading stops, without claiming a complete output plan.
     * Measured-family counts describe only the fully parsed prefix. Coverage, merge counts and
     * refreshed loads are cleared because later reports could change those tentative decisions.
     * @param failure safe failure code
     * @param diagnostic payload-free diagnostic
     * @return failed evidence with unknown output loads and merge counters
     */
    public HistoryRefreshReport failedRead(Reason failure, String diagnostic) {
        return new HistoryRefreshReport(Status.FAILED, failure, diagnostic,
                new Counts(counts.measured(), counts.measuredSuites(), null, null, null,
                        counts.foreign(), counts.skipped(), counts.retried(), counts.ambiguous(), counts.failed()),
                eligible, pruning, null, shardCount, previousLoad, null, diagnostics);
    }

    private String diagnosticJson() {
        var fields = new java.util.ArrayList<String>();
        new TreeMap<>(diagnostics).forEach((key, value) -> fields.add(quote(key.name()) + ":" + value));
        return "{" + String.join(",", fields) + "}";
    }

    private static Double imbalance(List<Double> load) {
        if (load == null) return null;
        // Scaling first avoids overflow while averaging otherwise valid large finite loads.
        double max = load.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (max == 0) return 1.0; // All-zero recorded work is perfectly balanced, not NaN.
        double average = load.stream().mapToDouble(value -> value / max).average().orElse(0);
        return 1.0 / average;
    }

    private static String quote(String text) {
        var value = new StringBuilder("\"");
        for (char character : text.toCharArray()) {
            switch (character) {
                case '"', '\\' -> value.append('\\').append(character);
                case '\n' -> value.append("\\n");
                case '\r' -> value.append("\\r");
                case '\t' -> value.append("\\t");
                default -> {
                    if (character < 32) value.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else value.append(character);
                }
            }
        }
        return value.append('"').toString();
    }
}
