package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static io.github.sjacek.shardpilot.DurationHistoryRefresh.*;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.Reason;
import static org.junit.jupiter.api.Assertions.*;

class DurationHistoryRefreshTest {
    private final Options defaults = Options.defaults();

    @Test void replacesWholeFamiliesAndRetainsOnlyEligibleUnmeasuredRecords() {
        var previous = Map.of("example.Outer", 20.0, "example.Outer$Inner", 30.0, "example.Kept$Nested", 4.0, "example.Gone", 1.0);
        var raw = List.of(suite("example.Outer", .02), suite("example.Outer$Inner", .02), suite("example.Foreign", 20));
        var result = refresh(previous, raw, eligible("example.Outer", "example.Kept"), defaults);
        assertTrue(result.passed());
        assertEquals(Map.of("example.Outer", .1, "example.Kept$Nested", 4.0), result.history());
        var counts = result.report().counts();
        assertEquals(1, counts.measured()); assertEquals(2, counts.measuredSuites());
        assertEquals(1, counts.kept()); assertEquals(1, counts.dropped()); assertEquals(2, counts.replaced());
        assertEquals(1, counts.foreign()); assertEquals(.5, result.report().coverage());
        assertEquals(previous, Map.of("example.Outer", 20.0, "example.Outer$Inner", 30.0, "example.Kept$Nested", 4.0, "example.Gone", 1.0));
    }

    @Test void acceptsInclusiveMavenFamilyWithZeroHeaderTestsAndRejectsOverlappingMemberEvidence() {
        var inclusive = new JUnitXmlTimings.Suite("example.Outer", .9, 0, 0, 0, 0,
                List.of(test("example.Outer", "outer", false), test("example.Outer$Inner", "nested", false)), "maven");
        var accepted = refresh(Map.of("example.Outer$Inner", 2.0), List.of(inclusive), eligible("example.Outer"), defaults);
        assertTrue(accepted.passed()); assertEquals(Map.of("example.Outer", .9), accepted.history());
        for (Duplicates policy : Duplicates.values()) {
            var rejected = refresh(Map.of(), List.of(inclusive, suite("example.Outer$Inner", .7)), eligible("example.Outer"), options(policy, false, 0));
            assertFalse(rejected.passed()); assertEquals(Reason.OVERLAPPING_FAMILY, rejected.report().reason());
            assertTrue(rejected.history().isEmpty());
        }
    }

    @Test void excludesSkippedSuitesWithoutInventingMinimumMeasurements() {
        var skipped = new JUnitXmlTimings.Suite("example.Skipped", .02, 2, 0, 0, 0,
                List.of(new JUnitXmlTimings.TestCase("example.Skipped", "one", true, false, false)), "skipped");
        var empty = new JUnitXmlTimings.Suite("example.Empty", .04, 0, 0, 0, 0, List.of(), "empty");
        var result = refresh(Map.of("example.Skipped", 3.0), List.of(skipped, empty, suite("example.Real", 0)),
                eligible("example.Skipped", "example.Empty", "example.Real"), defaults);
        assertTrue(result.passed()); assertEquals(2, result.report().counts().skipped());
        assertEquals(Map.of("example.Skipped", 3.0, "example.Real", .1), result.history());
    }

    @Test void retriesAndAmbiguousIdentitiesBlockTheEntireFamilyEvenWhenFailuresAreAllowed() {
        var retried = new JUnitXmlTimings.Suite("example.Outer$Inner", 1, 1, 0, 0, 0,
                List.of(test("example.Outer$Inner", "flaky", true)), "retry");
        var repeated = new JUnitXmlTimings.Suite("example.Repeated", 1, 2, 0, 0, 0,
                List.of(test("example.Repeated", "same", false), test("example.Repeated", "same", false)), "ambiguous");
        var result = refresh(Map.of("example.Outer", 7.0, "example.Outer$Inner", 8.0),
                List.of(suite("example.Outer", 2), retried, repeated, suite("example.Valid", 1)),
                eligible("example.Outer", "example.Repeated", "example.Valid"), options(Duplicates.FAIL, true, 0));
        assertTrue(result.passed()); assertEquals(1, result.report().counts().retried());
        assertEquals(2, result.report().counts().ambiguous());
        assertEquals(1, result.report().diagnostics().get(Reason.INCOMPLETE_FAMILY));
        assertEquals(Map.of("example.Outer", 7.0, "example.Outer$Inner", 8.0, "example.Valid", 1.0), result.history());
    }

    @Test void nonRetriedFailuresAreOptInAndBlockCleanFamilyMembersByDefault() {
        var failed = new JUnitXmlTimings.Suite("example.Outer$Inner", 2, 1, 0, 1, 0, List.of(), "failed");
        var raw = List.of(suite("example.Outer", 1), failed);
        var excluded = refresh(Map.of("example.Outer", 10.0), raw, eligible("example.Outer"), defaults);
        assertFalse(excluded.passed()); assertEquals(1, excluded.report().counts().failed());
        assertEquals(1, excluded.report().counts().ambiguous());
        var included = refresh(Map.of(), raw, eligible("example.Outer"), options(Duplicates.FAIL, true, 0));
        assertEquals(Map.of("example.Outer", 3.0), included.history());
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "skipped", "failed"})
    void detectsDuplicatesBeforeAnyExclusion(String category) {
        var raw = new JUnitXmlTimings.Suite("example.Duplicate", 1, category.equals("skipped") ? 0 : 1,
                0, category.equals("failed") ? 1 : 0, 0, List.of(), "first");
        // Each non-foreign case must actually be eligible, otherwise all variants exercise only foreign filtering.
        var result = refresh(Map.of(), List.of(raw, raw), eligible(category.equals("foreign") ? "example.Valid" : "example.Duplicate"), defaults);
        assertEquals(Reason.DUPLICATE_SUITE, result.report().reason());
        assertTrue(result.report().detail().contains("first"));
        assertTrue(result.report().json().contains("\"status\":\"FAILED\""));
    }

    @Test void sumDoesNotInventAmbiguousTestcaseIdentityButPreservesRetryFlags() {
        var original = new JUnitXmlTimings.Suite("example.Summed", 1, 1, 0, 0, 0,
                List.of(test("example.Summed", "same", false)), "first");
        var result = refresh(Map.of(), List.of(original, original), eligible("example.Summed"), options(Duplicates.SUM, false, 0));
        assertTrue(result.passed()); assertEquals(Map.of("example.Summed", 2.0), result.history());
        var retry = new JUnitXmlTimings.Suite("example.Summed", 1, 1, 0, 0, 0,
                List.of(test("example.Summed", "same", true)), "retry");
        var excluded = refresh(Map.of(), List.of(original, retry), eligible("example.Summed"), options(Duplicates.SUM, true, 0));
        assertEquals(Reason.NO_MEASUREMENTS, excluded.report().reason());
        assertEquals(1, excluded.report().counts().retried());
    }

    @Test void distinguishesDisabledEmptyAndPartialCoverage() {
        var raw = List.of(suite("example.A", 1));
        var disabled = refresh(Map.of("example.Kept", 2.0), raw, Eligibility.disabled(), defaults);
        assertTrue(disabled.passed()); assertNull(disabled.report().eligible()); assertNull(disabled.report().coverage());
        assertEquals(2, disabled.history().size());
        var empty = refresh(Map.of(), raw, eligible(), defaults);
        assertEquals(Reason.EMPTY_ELIGIBILITY, empty.report().reason()); assertEquals(1, empty.report().counts().foreign());
        var partial = refresh(Map.of(), raw, eligible("example.A", "example.B"), options(Duplicates.FAIL, false, .75));
        assertEquals(Reason.MINIMUM_COVERAGE, partial.report().reason()); assertEquals(.5, partial.report().coverage());
        assertTrue(partial.history().isEmpty());
        var failed = failed(Reason.INVALID_BASELINE, "Missing baseline", Source.BASELINE, null, defaults);
        assertNull(failed.report().eligible()); assertNull(failed.report().counts().measured());
        assertTrue(failed.report().json().contains("\"measured\":null"));
    }

    @Test void isIdempotentDeterministicAndUsesTheExistingPlannerForReportLoads() {
        var raw = List.of(suite("example.A", 6.12), suite("example.B", 2.34), suite("example.C", 1));
        var options = new Options(.1, 1, false, Duplicates.FAIL, 0, 2);
        var first = refresh(Map.of("example.A", 100.0), raw, eligible("example.A", "example.B", "example.C"), options);
        var reversed = new java.util.ArrayList<>(raw);
        java.util.Collections.reverse(reversed);
        var second = refresh(first.history(), reversed, eligible("example.C", "example.B", "example.A"), options);
        assertEquals(DurationHistory.format(first.history(), 1), DurationHistory.format(second.history(), 1));
        var plan = ShardPlan.create(first.history(), 2);
        assertEquals(List.of(plan.recordedSeconds(1), plan.recordedSeconds(2)), first.report().refreshedLoad());
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            assertEquals(first.report().json(), refresh(Map.of("example.A", 100.0), raw, eligible("example.A", "example.B", "example.C"), options).report().json());
            assertFalse(DurationHistory.format(first.history(), 1).contains("6,1"));
        } finally { Locale.setDefault(previous); }
    }

    @Test void validatesPrecisionMinimumCoverageAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new Options(.1, 0, false, Duplicates.FAIL, 0, 1));
        assertDoesNotThrow(() -> new Options(1, 0, false, Duplicates.FAIL, 0, 1));
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Options(value, 1, false, Duplicates.FAIL, 0, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new Options(.1, 7, false, Duplicates.FAIL, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Options(.1, 1, false, Duplicates.FAIL, 1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Options(.1, 1, false, Duplicates.FAIL, 0, 0));
        var overflow = refresh(Map.of(), List.of(suite("example.A", Double.MAX_VALUE), suite("example.A$Nested", Double.MAX_VALUE)), eligible("example.A"), defaults);
        assertEquals(Reason.INVALID_DATA, overflow.report().reason());
    }

    @Test void formatterRoundTripsBinaryNamesAndZeroLoadsDoNotProduceNan() {
        var history = Map.of("example.B", 0.0, "example.A$Nested", 12.34);
        String text = DurationHistory.format(history, 2);
        assertTrue(text.startsWith("#")); assertFalse(text.contains("\r"));
        assertTrue(text.indexOf("example.A$") < text.indexOf("example.B"));
        assertEquals(history, DurationHistory.parse(text));
        var result = refresh(Map.of(), List.of(suite("example.A", 0)), eligible("example.A"), defaults);
        assertTrue(result.report().json().contains("\"previous\":1.0"));
        assertFalse(result.report().json().contains("NaN"));
    }

    @Test void reportIsParseableAndCounterUnitsReconcileWithSerializedLoads() {
        var previous = Map.of("example.A", 10.0, "example.A$Nested", 5.0, "example.B", 1.234, "example.Removed", 1.0);
        var result = refresh(previous, List.of(suite("example.A", 1), suite("example.A$Nested", 2)), eligible("example.A", "example.B"), defaults);
        var counts = result.report().counts();
        assertEquals(previous.size(), counts.kept() + counts.dropped() + counts.replaced());
        assertEquals(result.history().size(), counts.kept() + counts.measured());
        assertEquals(2, counts.foreign() + counts.skipped() + counts.retried() + counts.ambiguous() + counts.failed() + counts.measuredSuites());
        var parsed = (Map<?, ?>) new org.snakeyaml.engine.v2.api.Load(
                org.snakeyaml.engine.v2.api.LoadSettings.builder().build()).loadFromString(result.report().json());
        assertEquals("PASSED", parsed.get("status"));
        assertEquals(result.history(), DurationHistory.parse(DurationHistory.format(result.history(), defaults.precision())));
        assertEquals(List.of(ShardPlan.create(result.history(), 1).recordedSeconds(1)), result.report().refreshedLoad());
        assertEquals(1.2, result.history().get("example.B"));
        var duplicate = refresh(previous, List.of(suite("example.A", 1), suite("example.A", 2)), eligible("example.A"), defaults);
        assertNull(duplicate.report().counts().kept());
        assertNull(duplicate.report().refreshedLoad());
    }

    @Test void sumPreservesFailureCountersAndReportsOverflowBeforeFiltering() {
        var failed = new JUnitXmlTimings.Suite("example.A", 2, 1, 0, 0, 1, List.of(), "failed");
        var result = refresh(Map.of(), List.of(suite("example.A", 1), failed), eligible("example.A"), options(Duplicates.SUM, false, 0));
        assertEquals(1, result.report().counts().failed());
        var huge = new JUnitXmlTimings.Suite("example.A", 1, Integer.MAX_VALUE, 0, 0, 0, List.of(), "huge");
        var overflow = refresh(Map.of(), List.of(huge, huge), eligible("example.Foreign"), options(Duplicates.SUM, false, 0));
        assertEquals(Reason.INVALID_DATA, overflow.report().reason());
    }

    @Test void skippedMembersDoNotBlockExecutedSiblingsAndRoundingHappensOnceForTheFamily() {
        var skipped = new JUnitXmlTimings.Suite("example.A$Skipped", 10, 1, 1, 0, 0, List.of(), "skipped");
        var result = refresh(Map.of("example.A$Skipped", 40.0),
                List.of(suite("example.A", .14), suite("example.A$Executed", .14), skipped), eligible("example.A"), defaults);
        assertTrue(result.passed());
        assertEquals(Map.of("example.A", .3), result.history());
        assertEquals(1, result.report().counts().skipped()); assertEquals(2, result.report().counts().measuredSuites());
        assertEquals(1, result.report().counts().replaced());
    }

    @Test void acceptsTheExactCoverageThresholdAndPrunesUnmeasuredOldNestedFamilies() {
        var result = refresh(Map.of("example.Removed$Nested", 3.0, "example.Kept$Nested", 4.0),
                List.of(suite("example.A", 2)), eligible("example.A", "example.Kept"), options(Duplicates.FAIL, false, .5));
        assertTrue(result.passed()); assertEquals(.5, result.report().coverage());
        assertEquals(Map.of("example.A", 2.0, "example.Kept$Nested", 4.0), result.history());
        assertEquals(1, result.report().counts().dropped()); assertEquals(1, result.report().counts().kept());
    }

    @Test void refreshDoesNotMutateCallerInputsAndExposesImmutableResults() {
        var previous = new java.util.LinkedHashMap<>(Map.of("example.A", 2.0, "example.Kept", 3.0));
        var before = new java.util.LinkedHashMap<>(previous);
        var families = new java.util.HashSet<>(Set.of("example.A$Nested", "example.Kept"));
        var eligibility = new Eligibility(Source.CLASS_DIRECTORIES, families);
        families.clear();
        var raw = new java.util.ArrayList<>(List.of(suite("example.A", 1)));
        var result = refresh(previous, raw, eligibility, defaults);
        assertEquals(before, previous); assertEquals(1, raw.size());
        assertEquals(Set.of("example.A", "example.Kept"), eligibility.families());
        assertThrows(UnsupportedOperationException.class, () -> result.history().put("example.B", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> eligibility.families().clear());
    }

    @ParameterizedTest @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidPreviousTimingsReturnFailureWithoutPublishingAHistory(double seconds) {
        var result = refresh(Map.of("example.A", seconds), List.of(suite("example.A", 1)), eligible("example.A"), defaults);
        assertFalse(result.passed()); assertTrue(result.history().isEmpty());
        assertEquals(Reason.INVALID_HISTORY, result.report().reason());
        assertNull(result.report().counts().measured()); assertNull(result.report().previousLoad());
    }

    @ParameterizedTest @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidCoverageIsRejectedAndDisabledPruningCannotApplyAPositiveThreshold(double coverage) {
        assertThrows(IllegalArgumentException.class, () -> options(Duplicates.FAIL, false, coverage));
        assertThrows(IllegalArgumentException.class, () -> refresh(Map.of(), List.of(suite("example.A", 1)),
                Eligibility.disabled(), options(Duplicates.FAIL, false, .5)));
    }

    private static Eligibility eligible(String... names) { return new Eligibility(Source.BASELINE, Set.of(names)); }
    private static Options options(Duplicates duplicates, boolean failed, double coverage) { return new Options(.1, 1, failed, duplicates, coverage, 1); }
    private static JUnitXmlTimings.Suite suite(String name, double seconds) { return new JUnitXmlTimings.Suite(name, seconds, 1, 0, 0, 0, List.of(), name); }
    private static JUnitXmlTimings.TestCase test(String name, String method, boolean retried) { return new JUnitXmlTimings.TestCase(name, method, false, false, retried); }
}
