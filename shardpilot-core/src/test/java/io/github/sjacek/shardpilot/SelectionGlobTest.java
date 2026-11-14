package io.github.sjacek.shardpilot;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionGlobTest {
    @Test
    void doubleStarMatchesWholeSegmentsIncludingZeroSegments() {
        assertTrue(path("**/*.md", "README.md"));
        assertTrue(path("src/**/Test?.java", "src/Test1.java"));
        assertTrue(path("src/**/Test?.java", "src/a/b/Test2.java"));
        assertFalse(path("src/*.java", "src/a/Test.java"));
        assertFalse(path("src/**/Test?.java", "src/Test10.java"));
        assertTrue(SelectionGlob.matches("**.Alpha*", "AlphaIT", "\\.", new SelectionGlob.Work()));
        assertTrue(SelectionGlob.matches("example.**.Alpha*", "example.deep.AlphaIT$Nested", "\\.", new SelectionGlob.Work()));
    }

    @Test
    void wildcardsUseUnicodeCodePointsAndTreatRegexSyntaxLiterally() {
        assertTrue(path("src/?.java", "src/𐐀.java"));
        assertFalse(path("src/??.java", "src/𐐀.java"));
        assertTrue(path("docs/[guide](v1)+.md", "docs/[guide](v1)+.md"));
        assertFalse(path("docs/[guide](v1)+.md", "docs/guidev1.md"));
    }

    @Test
    void evaluationCeilingProducesFailedEvidenceRatherThanPartialSuccess() {
        // One valid but expensive pattern exhausts the shared per-call DP allowance.
        String pattern = "*a".repeat(250);
        String name = "a".repeat(500);
        var eligible = new java.util.HashSet<String>();
        for (int index = 0; index < 100; index++) eligible.add(name + index);
        var profile = Profile.smoke(new Targets(Set.of(), Set.of(pattern)), Budget.unlimited());
        var decision = SelectionEngine.select(new SelectionPolicy(1, Map.of("limit-testdata", profile)),
                new SelectionContext("limit-testdata", eligible, SelectionContext.ChangeSet.unknown(), Map.of(), Set.of()));
        assertEquals(SelectionDecision.Status.FAILED, decision.status());
        assertTrue(decision.reasons().contains(SelectionDecision.Reason.RESOURCE_LIMIT));
        assertTrue(decision.selected().isEmpty());
    }

    private static boolean path(String pattern, String value) {
        SelectionGlob.path(pattern, true);
        SelectionGlob.path(value, false);
        return SelectionGlob.matches(pattern, value, "/", new SelectionGlob.Work());
    }
}
