package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Presentation contracts include partial evidence, plan-only mode and untrusted parser diagnostics. */
class ShardCoverageHtmlTest {
    @TempDir Path directory;
    Path plan;
    Path actual;

    @BeforeEach
    void fixture() throws IOException {
        plan = Files.createDirectory(directory.resolve("plan"));
        actual = Files.createDirectory(directory.resolve("actual"));
        write(plan, "baseline", "example.ŻółćTest$Nested", "example.AlphaTest");
        write(plan, "planned-1", "example.ŻółćTest$Nested");
        write(plan, "planned-2", "example.AlphaTest");
        write(actual, "actual-1", "example.ŻółćTest$Nested");
        write(actual, "actual-2", "example.AlphaTest");
    }

    @Test
    void successListsSortedClassesAndBothAssignmentsWithoutExternalAssetsOrScripts() {
        String html = render(true);
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("Verification: PASSED"));
        assertTrue(html.contains("Baseline: 2 classes. Shards: 2"));
        assertTrue(html.contains("example.AlphaTest</code></th><td>Yes</td><td>2</td><td>2</td><td>Matches"));
        assertTrue(html.contains("example.ŻółćTest$Nested</code></th><td>Yes</td><td>1</td><td>1</td><td>Matches"));
        assertTrue(html.indexOf("example.AlphaTest") < html.indexOf("example.ŻółćTest$Nested"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("src="));
        assertFalse(html.contains("href=\"http"));
        assertTrue(html.contains("does not prove test execution or success"));
    }

    @Test
    void wrongNodeArtifactsShowBothDifferencesEvenWhenTheUnionMatches() throws IOException {
        write(actual, "actual-1", "example.AlphaTest");
        write(actual, "actual-2", "example.ŻółćTest$Nested");
        String html = render(true);
        assertTrue(html.contains("Verification: FAILED"));
        assertTrue(html.contains("Shard 1 differs from plan"));
        assertTrue(html.contains("example.AlphaTest</code></th><td>Yes</td><td>2</td><td>1</td><td>Differs"));
        assertEquals(2, html.split("Differs: 1 missing, 1 unexpected", -1).length - 1);
    }

    @Test
    void missingAndInvalidFilesRemainUnavailableWhileValidShardsAreVisible() throws IOException {
        Files.delete(actual.resolve("actual-1.yaml"));
        Files.writeString(plan.resolve("planned-1.yaml"), "classes: invalid\n");
        String html = render(true);
        assertTrue(html.contains("Verification: FAILED"));
        assertTrue(html.contains("planned-1.yaml:</strong>"));
        assertTrue(html.contains("actual-1.yaml:</strong> Missing class manifest"));
        assertTrue(html.contains("<th scope=\"row\">1</th><td>Unavailable</td><td>Unavailable</td>"));
        assertTrue(html.contains("example.AlphaTest</code></th><td>Yes</td><td>2</td><td>2</td><td>Evidence unavailable"));
        assertTrue(html.contains("example.ŻółćTest$Nested</code></th><td>Yes</td><td>None</td><td>None</td><td>Evidence unavailable"));
    }

    @Test
    void planOnlyReportDoesNotReadOrClaimActualEvidence() throws IOException {
        Files.writeString(actual.resolve("actual-1.yaml"), "invalid metadata");
        Files.delete(actual.resolve("actual-2.yaml"));
        String html = render(false);
        assertTrue(html.contains("Verification: PASSED"));
        assertTrue(html.contains("disabled (plan only)"));
        assertTrue(html.contains("example.AlphaTest</code></th><td>Yes</td><td>2</td><td>Not checked</td><td>Not checked"));
        assertFalse(html.contains("actual-1.yaml:</strong>"));
    }

    @Test
    void foreignOmittedAndMultiplyAssignedClassesRemainVisibleOnFailure() throws IOException {
        write(plan, "planned-2", "example.ŻółćTest$Nested", "example.ForeignTest");
        write(actual, "actual-2", "example.ForeignTest");
        String html = render(true);
        assertTrue(html.contains("Verification: FAILED"));
        assertTrue(html.contains("example.AlphaTest</code></th><td>Yes</td><td>None</td><td>None</td><td>Missing from plan"));
        assertTrue(html.contains("example.ForeignTest</code></th><td>No</td><td>2</td><td>2</td><td>Outside baseline"));
        assertTrue(html.contains("example.ŻółćTest$Nested</code></th><td>Yes</td><td>1, 2</td><td>1</td><td>Multiple planned shards"));
    }

    @Test
    void invalidArtifactTextIsEscapedAsTextRatherThanHtml() throws IOException {
        write(plan, "baseline", "<script>alert(\"Żółć & 'x'\")</script>");
        String html = render(true);
        assertTrue(html.contains("Verification: FAILED"));
        assertTrue(html.contains("&lt;script&gt;alert(&quot;Żółć &amp; &#39;x&#39;&quot;)&lt;/script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("Baseline unavailable"));
    }

    @Test
    void bothFormatsUseTheSameSnapshotEvenIfArtifactsChangeAfterEvaluation() throws IOException {
        var result = ShardCoverage.evaluate(plan, actual, 2, true, true);
        String html = ShardCoverageHtml.render(result);
        write(actual, "actual-1", "example.ForeignTest");
        assertEquals(html, ShardCoverageHtml.render(result));
        assertTrue(result.json().contains("\"status\":\"passed\""));
        assertTrue(render(true).contains("Verification: FAILED"));
    }

    @Test
    void publicSnapshotCopiesCallerCollectionsAndCannotBeMutatedThroughAccessors() {
        // Public core records can be constructed by either adapter; they must detach nested data too.
        var classes = new HashSet<>(Set.of("example.OneTest"));
        var manifest = new ShardCoverage.Manifest(classes, null);
        var planned = new HashMap<>(Map.of(1, manifest));
        var actual = new HashMap<>(planned);
        var snapshot = new ShardCoverage.Evaluation("{\"status\":\"passed\"}\n", null, manifest, planned, actual, true);
        String before = ShardCoverageHtml.render(snapshot);
        classes.add("example.ForeignTest");
        planned.clear();
        actual.clear();
        assertEquals(Set.of("example.OneTest"), snapshot.baseline().classes());
        assertEquals(Map.of(1, manifest), snapshot.planned());
        assertEquals(Map.of(1, manifest), snapshot.actual());
        assertEquals(before, ShardCoverageHtml.render(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> manifest.classes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.planned().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.actual().clear());
    }

    @Test
    void explicitlyAllowedEmptyShardsShowZeroAndStaleExtrasStillFail() throws IOException {
        write(plan, "planned-3");
        write(actual, "actual-3");
        String html = ShardCoverageHtml.render(ShardCoverage.evaluate(plan, actual, 3, true, false));
        assertTrue(html.contains("Verification: PASSED"));
        assertTrue(html.contains("<th scope=\"row\">3</th><td>0</td><td>0</td><td>Matches"));
        assertTrue(render(true).contains("Unexpected planned manifest set"));
    }

    private String render(boolean verifyActual) {
        return ShardCoverageHtml.render(ShardCoverage.evaluate(plan, actual, 2, verifyActual, true));
    }

    private static void write(Path directory, String name, String... classes) throws IOException {
        Files.writeString(directory.resolve(name + ".yaml"), ManifestFixtures.yaml(classes));
    }
}
