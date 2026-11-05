package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static io.github.sjacek.shardpilot.DurationHistoryRefresh.Source;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the published report shape independently of the refresh algorithm. */
class HistoryRefreshReportTest {
    @Test
    void preservesCounterUnitsAndOmitsFailureFieldsOnSuccess() {
        var report = new HistoryRefreshReport(Status.PASSED, null, null,
                new Counts(2, 3, 4, 5, 6, 7, 8, 9, 10, 11), 4, Source.BASELINE, .5, 2,
                List.of(3.0, 1.0), List.of(2.0, 2.0), Map.of(Reason.INCOMPLETE_FAMILY, 2));
        var json = parse(report);
        assertEquals("PASSED", json.get("status"));
        assertFalse(json.containsKey("reason")); assertFalse(json.containsKey("detail"));
        assertEquals(2, json.get("measured")); assertEquals(3, json.get("measuredSuites"));
        assertEquals(4, json.get("kept")); assertEquals(5, json.get("dropped")); assertEquals(6, json.get("replaced"));
        assertEquals(Map.of("skipped", 8, "retried", 9, "ambiguous", 10, "failed", 11), json.get("ignored"));
        assertEquals(Map.of("previous", List.of(3.0, 1.0), "refreshed", List.of(2.0, 2.0)), json.get("recordedLoad"));
        assertEquals(Map.of("previous", 1.5, "refreshed", 1.0), json.get("imbalance"));
        assertEquals(Map.of("INCOMPLETE_FAMILY", 2), json.get("diagnostics"));
        assertTrue(report.json().endsWith("\n"));
        assertFalse(report.lifecycle().contains("\n"));
    }

    @Test
    void escapesDiagnosticsButNeverCopiesThemToLifecycleOutput() {
        String detail = "input-1/TEST-quoted\"\\name\n\r\t\b\f.xml: SAFE_DIAGNOSTIC";
        var report = DurationHistoryRefresh.failed(Reason.INVALID_XML, detail, Source.BASELINE, null,
                DurationHistoryRefresh.Options.defaults()).report();
        var json = parse(report);
        assertEquals(detail, json.get("detail"));
        assertEquals("INVALID_XML", json.get("reason"));
        assertNull(json.get("measured")); assertNull(json.get("eligible")); assertNull(json.get("coverage"));
        assertEquals("INVALID_XML", report.reason().name());
        assertFalse(report.lifecycle().contains("SAFE_DIAGNOSTIC"));
        assertFalse(report.lifecycle().contains("\n"));
    }

    @Test
    void failedReadKeepsPrefixEvidenceWithoutClaimingFinalCoverageOrOutputLoads() {
        var successfulPrefix = new HistoryRefreshReport(Status.PASSED, null, null,
                new Counts(1, 2, 3, 4, 5, 0, 1, 0, 1, 0), 8, Source.CLASS_DIRECTORIES, .125, 2,
                List.of(5.0, 3.0), List.of(2.0, 2.0), Map.of(Reason.INCOMPLETE_FAMILY, 1));
        var failed = successfulPrefix.failedRead(Reason.INVALID_XML, "Incomplete document");
        assertEquals(Status.FAILED, failed.status());
        assertEquals(1, failed.counts().measured()); assertEquals(2, failed.counts().measuredSuites());
        assertEquals(1, failed.counts().ambiguous());
        assertNull(failed.counts().kept()); assertNull(failed.counts().dropped()); assertNull(failed.counts().replaced());
        assertNull(failed.coverage()); assertNull(failed.refreshedLoad());
        assertEquals(List.of(5.0, 3.0), failed.previousLoad());
        assertEquals(8, failed.eligible());
        assertEquals(Map.of(Reason.INCOMPLETE_FAMILY, 1), failed.diagnostics());
        assertNull(((Map<?, ?>) parse(failed).get("imbalance")).get("refreshed"));
        assertEquals(Status.PASSED, successfulPrefix.status());
    }

    @Test
    void snapshotsCollectionsAndProducesTheSameJsonAcrossLocaleAndMapOrder() {
        var previous = new ArrayList<>(List.of(10.0, 0.0));
        var refreshed = new ArrayList<>(List.of(4.0, 4.0));
        var diagnostics = new LinkedHashMap<Reason, Integer>();
        diagnostics.put(Reason.INCOMPLETE_FAMILY, 2); diagnostics.put(Reason.INVALID_DATA, 1);
        var report = new HistoryRefreshReport(Status.FAILED, Reason.INVALID_XML, "Safe detail",
                new Counts(1, 1, null, null, null, 0, 0, 0, 2, 0), 4, Source.BASELINE, null, 2,
                previous, refreshed, diagnostics);
        String expected = report.json();
        previous.clear(); refreshed.clear(); diagnostics.clear();
        assertEquals(List.of(10.0, 0.0), report.previousLoad());
        assertEquals(List.of(4.0, 4.0), report.refreshedLoad());
        assertThrows(UnsupportedOperationException.class, () -> report.previousLoad().add(1.0));
        assertThrows(UnsupportedOperationException.class, () -> report.diagnostics().clear());
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            var reordered = new HistoryRefreshReport(report.status(), report.reason(), report.detail(), report.counts(),
                    report.eligible(), report.pruning(), report.coverage(), report.shardCount(), report.previousLoad(),
                    report.refreshedLoad(), Map.of(Reason.INVALID_DATA, 1, Reason.INCOMPLETE_FAMILY, 2));
            assertEquals(expected, reordered.json());
        } finally { Locale.setDefault(original); }
    }

    @Test
    void zeroAndVeryLargeLoadsHaveFiniteImbalance() {
        for (List<Double> loads : List.of(List.of(0.0, 0.0), List.of(Double.MAX_VALUE, Double.MAX_VALUE))) {
            var report = new HistoryRefreshReport(Status.PASSED, null, null,
                    new Counts(1, 1, 0, 0, 0, 0, 0, 0, 0, 0), null, Source.DISABLED, null, 2,
                    loads, List.of(8.0, 0.0), Map.of());
            assertEquals(Map.of("previous", 1.0, "refreshed", 2.0), parse(report).get("imbalance"));
            assertFalse(report.json().contains("NaN")); assertFalse(report.json().contains("Infinity"));
        }
    }

    private static Map<?, ?> parse(HistoryRefreshReport report) {
        return (Map<?, ?>) new Load(LoadSettings.builder().build()).loadFromString(report.json());
    }
}
