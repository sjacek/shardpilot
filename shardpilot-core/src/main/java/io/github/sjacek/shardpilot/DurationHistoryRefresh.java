package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.Reason;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.Status;

/**
 * Pure refresh policy shared by build-tool adapters; never writes or modifies input files.
 * Raw-suite integrity is checked before exclusions, while timing normalization operates on
 * complete outer-class families. A failed result carries diagnostics but never a candidate history.
 */
public final class DurationHistoryRefresh {
    private DurationHistoryRefresh() { }

    /** Duplicate raw suite names fail by default; SUM is an explicit consumer policy. */
    public enum Duplicates {
        /** Reject any repeated raw suite name, before exclusions. */
        FAIL,
        /** Explicitly add repeated suite times and retain all exclusion flags. */
        SUM
    }

    /** Resolved source, kept distinct from an empty configured family set. */
    public enum Source {
        /** Validated class manifest. */
        BASELINE("baseline"),
        /** Binary names derived from supplied bytecode roots. */
        CLASS_DIRECTORIES("classDirectories"),
        /** No eligibility pruning or coverage threshold. */
        DISABLED("disabled");
        private final String label;
        Source(String label) { this.label = label; }
        /**
         * Stable JSON source name.
         * @return stable JSON source name
         */
        public String label() { return label; }
    }

    /**
     * Eligible families, or an explicit disabled-pruning state. A configured empty set accepts
     * no measurements; DISABLED accepts every family and does not calculate coverage.
     * @param source resolved source, distinct from its content
     * @param families validated binary names, normalized to outer families
     */
    public record Eligibility(Source source, Set<String> families) {
        /** Validates and normalizes a defensive copy to the planner's outer families. */
        public Eligibility {
            java.util.Objects.requireNonNull(source);
            var names = new TreeSet<String>();
            for (String name : families) {
                if (DurationHistory.invalidClassName(name)) throw new IllegalArgumentException("Invalid eligible binary class name");
                names.add(DurationHistory.family(name));
            }
            if (source == Source.DISABLED && !names.isEmpty()) throw new IllegalArgumentException("Disabled pruning has no eligible set");
            families = Set.copyOf(names);
        }
        /**
         * Explicit no-pruning eligibility.
         * @return explicit no-pruning eligibility
         */
        public static Eligibility disabled() { return new Eligibility(Source.DISABLED, Set.of()); }
        /**
         * Checks family participation without treating a configured empty set as disabled pruning.
         * @param family outer family name
         * @return whether that family participates
         */
        public boolean includes(String family) { return source == Source.DISABLED || families.contains(family); }
    }

    /**
     * Validated options; an invalid configuration is rejected before XML or baseline data is read.
     * @param minimumSeconds positive family-time floor, nonzero at the configured precision
     * @param precision fractional decimal digits, zero to six
     * @param includeFailedSuites whether non-retried failed executions may be measured
     * @param duplicates duplicate raw suite-name policy
     * @param minimumCoverage fraction of eligible families required, zero to one
     * @param shardCount positive number of shards for recorded load reporting
     */
    public record Options(double minimumSeconds, int precision, boolean includeFailedSuites,
                          Duplicates duplicates, double minimumCoverage, int shardCount) {
        /** Rejects zero-rounding, nonfinite values and invalid partition/coverage settings. */
        public Options {
            if (precision < 0 || precision > 6) throw new IllegalArgumentException("ShardPilot precision must be between 0 and 6");
            if (!Double.isFinite(minimumSeconds) || minimumSeconds <= 0
                    || DurationHistory.rounded(minimumSeconds, precision).signum() == 0) {
                throw new IllegalArgumentException("ShardPilot minimumSeconds must be finite, positive and nonzero at the configured precision");
            }
            if (!Double.isFinite(minimumCoverage) || minimumCoverage < 0 || minimumCoverage > 1) {
                throw new IllegalArgumentException("ShardPilot minimumCoverage must be between 0 and 1");
            }
            java.util.Objects.requireNonNull(duplicates);
            ShardPlan.validateShard(shardCount, 1);
        }
        /**
         * Conservative default options.
         * @return conservative default options
         */
        public static Options defaults() { return new Options(.1, 1, false, Duplicates.FAIL, 0, 1); }
    }

    /**
     * A failure contains no candidate history that a caller could accidentally publish.
     * @param history candidate records on success; empty on failure
     * @param report immutable evidence, including unknown fields on early failure
     */
    public record Result(Map<String, Double> history, HistoryRefreshReport report) {
        /** Makes the successful history immutable. */
        public Result { history = Map.copyOf(history); }
        /**
         * Whether the candidate TSV may be written.
         * @return whether the candidate TSV may be written
         */
        public boolean passed() { return report.status() == Status.PASSED; }
    }

    /**
     * Refreshes complete measured families, preserving unmeasured eligible old records.
     * Duplicate detection precedes foreign/skipped/retry/failure exclusions. Clean siblings of an
     * excluded family member cannot replace part of an old family: they are counted as incomplete.
     * A successful family sample replaces every old outer/nested record with one family record;
     * its minimum and rounding are applied once. Kept records use the output precision too, so
     * reported loads always describe the exact TSV candidate rather than unrounded in-memory values.
     * @param previous validated prior history (binary records, possibly nested)
     * @param raw raw suites, before duplicate detection or exclusions
     * @param eligibility one resolved source, possibly configured-empty or disabled
     * @param options validated refresh settings
     * @return a successful history/report or a failed report with accumulated counts
     */
    public static Result refresh(Map<String, Double> previous, Collection<JUnitXmlTimings.Suite> raw,
                                 Eligibility eligibility, Options options) {
        if (eligibility.source() == Source.DISABLED && options.minimumCoverage() > 0) {
            throw new IllegalArgumentException("ShardPilot minimumCoverage requires enabled pruning");
        }
        // Reuse the history boundary's validation, including binary names and finite durations.
        try { DurationHistory.format(previous, options.precision()); }
        catch (IllegalArgumentException failure) { return failed(Reason.INVALID_HISTORY, "Invalid previous timing history", eligibility.source(), null, options); }
        var stats = new Statistics(eligibility, options);
        List<Double> oldLoads;
        try { oldLoads = loads(previous, options.shardCount()); }
        catch (IllegalArgumentException failure) { return stats.failure(Reason.INVALID_HISTORY, "Invalid previous recorded load", null, null); }
        var suites = new TreeMap<String, Combined>();
        // Order affects floating-point addition under SUM and which duplicate source is diagnosed.
        for (var suite : raw.stream().sorted(java.util.Comparator.comparing(JUnitXmlTimings.Suite::name)
                .thenComparing(JUnitXmlTimings.Suite::source).thenComparingDouble(JUnitXmlTimings.Suite::seconds)).toList()) {
            if (DurationHistory.invalidClassName(suite.name()) || !Double.isFinite(suite.seconds()) || suite.seconds() < 0) {
                return stats.failure(Reason.INVALID_DATA, "Invalid raw suite metadata", oldLoads, null);
            }
            Combined combined = suites.computeIfAbsent(suite.name(), ignored -> new Combined(suite.name()));
            if (!combined.raw.isEmpty() && options.duplicates() == Duplicates.FAIL) {
                String detail = "Duplicate suite in " + safe(combined.raw.get(0).source()) + " and " + safe(suite.source());
                return stats.failure(Reason.DUPLICATE_SUITE, detail, oldLoads, null);
            }
            try { combined.add(suite); }
            catch (ArithmeticException failure) { return stats.failure(Reason.INVALID_DATA, "Merged suite counter or time overflow", oldLoads, null); }
        }
        stats.classified = true;
        var candidates = new TreeMap<String, List<Combined>>();
        var blocked = new HashSet<String>();
        for (Combined suite : suites.values()) {
            String family = DurationHistory.family(suite.name);
            if (!eligibility.includes(family)) { stats.foreign++; continue; }
            if (!suite.executed()) { stats.skipped++; continue; }
            if (suite.raw.stream().anyMatch(JUnitXmlTimings.Suite::retried)) {
                stats.retried++; blocked.add(family); continue;
            }
            if (suite.raw.stream().anyMatch(JUnitXmlTimings.Suite::ambiguous)) {
                stats.ambiguous++; blocked.add(family); continue;
            }
            if (!options.includeFailedSuites() && suite.failed()) {
                stats.failed++; blocked.add(family); continue;
            }
            candidates.computeIfAbsent(family, ignored -> new ArrayList<>()).add(suite);
        }
        var measured = new TreeMap<String, Double>();
        stats.normalizing = true;
        for (var entry : candidates.entrySet()) {
            if (blocked.contains(entry.getKey())) {
                // Preserve the entire previous family, not a mixture of old and new member timings.
                stats.ambiguous += entry.getValue().size();
                stats.diagnostics.merge(Reason.INCOMPLETE_FAMILY, entry.getValue().size(), Integer::sum);
                continue;
            }
            var seen = new HashSet<String>();
            double total = 0;
            for (Combined suite : entry.getValue()) {
                var members = new TreeSet<String>();
                suite.raw.stream().filter(JUnitXmlTimings.Suite::executed).forEach(rawSuite -> members.addAll(rawSuite.members()));
                for (String member : members) {
                    // Inclusive enclosing suites and separate nested suites must not charge the same work twice.
                    if (!DurationHistory.family(member).equals(entry.getKey()) || !seen.add(member)) {
                        return stats.failure(Reason.OVERLAPPING_FAMILY, "Ambiguous member coverage within a class family", oldLoads, null);
                    }
                }
                total += suite.seconds;
                if (!Double.isFinite(total)) return stats.failure(Reason.INVALID_DATA, "Family duration overflow", oldLoads, null);
            }
            double seconds = DurationHistory.rounded(Math.max(total, options.minimumSeconds()), options.precision()).doubleValue();
            if (!Double.isFinite(seconds)) return stats.failure(Reason.INVALID_DATA, "Rounded duration overflow", oldLoads, null);
            measured.put(entry.getKey(), seconds);
            stats.measured++;
            stats.measuredSuites += entry.getValue().size();
        }
        var refreshed = new TreeMap<>(measured);
        stats.merged = true;
        for (var entry : new TreeMap<>(previous).entrySet()) {
            String family = DurationHistory.family(entry.getKey());
            if (!eligibility.includes(family)) stats.dropped++;
            else if (measured.containsKey(family)) stats.replaced++;
            else { refreshed.put(entry.getKey(), entry.getValue()); stats.kept++; }
        }
        stats.coverage = eligibility.source() == Source.DISABLED ? null
                : eligibility.families().isEmpty() ? 0.0 : (double) measured.size() / eligibility.families().size();
        List<Double> newLoads;
        try {
            // The report must describe the exact serialized candidate, including fixed precision of kept records.
            refreshed = new TreeMap<>(DurationHistory.parse(DurationHistory.format(refreshed, options.precision())));
            newLoads = loads(refreshed, options.shardCount());
        }
        catch (IllegalArgumentException failure) { return stats.failure(Reason.INVALID_DATA, "Refreshed recorded load overflow", oldLoads, null); }
        if (measured.isEmpty()) return stats.failure(eligibility.source() != Source.DISABLED && eligibility.families().isEmpty()
                ? Reason.EMPTY_ELIGIBILITY : Reason.NO_MEASUREMENTS, "No accepted family measurements", oldLoads, newLoads);
        if (stats.coverage != null && stats.coverage < options.minimumCoverage()) {
            return stats.failure(Reason.MINIMUM_COVERAGE, "Family coverage is below minimumCoverage", oldLoads, newLoads);
        }
        return new Result(refreshed, stats.report(Status.PASSED, null, null, oldLoads, newLoads));
    }

    /**
     * Builds diagnostic JSON evidence for failures occurring before refresh, such as a missing baseline.
     * @param reason safe failure code
     * @param detail diagnostic containing no parser/manifest payload
     * @param source selected eligibility source
     * @param eligible number of known eligible families, or null if unavailable
     * @param options validated settings
     * @return failure with unknown, rather than invented, counters
     */
    public static Result failed(Reason reason, String detail, Source source, Integer eligible, Options options) {
        return new Result(Map.of(), new HistoryRefreshReport(Status.FAILED, reason, detail,
                new HistoryRefreshReport.Counts(null, null, null, null, null, null, null, null, null, null),
                eligible, source, null, options.shardCount(), null, null, Map.of()));
    }

    private static List<Double> loads(Map<String, Double> history, int shards) {
        var plan = ShardPlan.create(history, shards);
        var loads = new ArrayList<Double>();
        for (int shard = 1; shard <= shards; shard++) loads.add(plan.recordedSeconds(shard));
        return List.copyOf(loads);
    }

    private static String safe(String text) { return text.replaceAll("[\\p{Cntrl}]", "?"); }

    /**
     * SUM retains raw suites: equal testcase identities across explicitly summed reports do not
     * imply retry. Ambiguity is assessed inside each raw suite, then its flags survive aggregation.
     */
    private static final class Combined {
        final String name;
        final List<JUnitXmlTimings.Suite> raw = new ArrayList<>();
        double seconds;
        int tests, skipped, failures, errors;
        Combined(String name) { this.name = name; }
        void add(JUnitXmlTimings.Suite suite) {
            tests = Math.addExact(tests, suite.tests()); skipped = Math.addExact(skipped, suite.skipped());
            failures = Math.addExact(failures, suite.failures()); errors = Math.addExact(errors, suite.errors());
            seconds += suite.seconds();
            if (!Double.isFinite(seconds)) throw new ArithmeticException();
            raw.add(suite);
        }
        boolean executed() { return raw.stream().allMatch(suite -> suite.cases().isEmpty())
                ? tests > 0 && skipped != tests : raw.stream().anyMatch(JUnitXmlTimings.Suite::executed); }
        boolean failed() { return failures > 0 || errors > 0 || raw.stream().anyMatch(JUnitXmlTimings.Suite::failed); }
    }

    /** Phase flags distinguish a measured zero from a count that could not yet be calculated. */
    private static final class Statistics {
        final Eligibility eligibility;
        final Options options;
        int measured, measuredSuites, kept, dropped, replaced, foreign, skipped, retried, ambiguous, failed;
        Double coverage;
        boolean classified, normalizing, merged;
        final Map<Reason, Integer> diagnostics = new EnumMap<>(Reason.class);
        Statistics(Eligibility eligibility, Options options) { this.eligibility = eligibility; this.options = options; }
        Result failure(Reason reason, String detail, List<Double> oldLoads, List<Double> newLoads) {
            return new Result(Map.of(), report(Status.FAILED, reason, detail, oldLoads, newLoads));
        }
        HistoryRefreshReport report(Status status, Reason reason, String detail, List<Double> oldLoads, List<Double> newLoads) {
            return new HistoryRefreshReport(status, reason, detail,
                    new HistoryRefreshReport.Counts(normalizing ? measured : null, normalizing ? measuredSuites : null,
                            merged ? kept : null, merged ? dropped : null, merged ? replaced : null,
                            classified ? foreign : null, classified ? skipped : null, classified ? retried : null,
                            classified ? ambiguous : null, classified ? failed : null),
                    eligibility.source() == Source.DISABLED ? null : eligibility.families().size(), eligibility.source(), coverage,
                    options.shardCount(), oldLoads, newLoads, diagnostics);
        }
    }
}
