package io.github.sjacek.shardpilot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.sjacek.shardpilot.SelectionDecision.*;
import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Protected coverage and declared fallbacks remain independent of budget-driven optional work. */
class SelectionEngineTest {
    private static final String PROFILE = "daily-testdata";
    private static final String A = "example.AlphaIT";
    private static final String B = "example.BetaIT";
    private static final String C = "example.GammaIT";
    private static final Set<String> ELIGIBLE = Set.of(A, B, C);

    @Test
    void unionsOverlappingRulesWithoutLettingIgnoreVetoRequiredTests() {
        var profile = Profile.affected(exact(A), List.of(
                rule("required", Action.REQUIRE, exact(B), "src/**"),
                rule("ignored", Action.IGNORE, Targets.none(), "src/**"),
                rule("optional", Action.OPTIONAL, exact(C), "src/main/**")), new Budget(2, 20, 60, Overflow.FAIL));
        var decision = select(profile, ELIGIBLE, Set.of("src/main/service.java"), Map.of(A, 1d, B, 2d, C, 3d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(Set.of("required", "ignored", "optional"), decision.matchedRules());
        assertEquals(Set.of("required"), decision.families().get(B).rules());
        assertTrue(decision.families().get(B).protectedTarget());
        assertEquals(Disposition.DEFERRED, decision.families().get(C).disposition());
        assertTrue(decision.families().get(C).reasons().contains(Reason.FAMILY_BUDGET));
    }

    @Test
    void nestedRequestUsesOneFamilyBudgetAndNeverInventsAnAbsentOuterClass() {
        String nested = A + "$Nested";
        String other = A + "$Other";
        var profile = Profile.smoke(exact(nested), new Budget(1, 6, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(nested, other, B), Set.of(), Map.of(nested, 2d, other, 3d));
        assertEquals(Set.of(nested, other), decision.selected());
        assertFalse(decision.selected().contains(A));
        assertEquals(5d, decision.estimatedSeconds());
        assertEquals(2, decision.families().size());
        assertEquals(5d, decision.families().get(A).estimatedSeconds());
    }

    @Test
    void requiredOverflowFailsWithNoExecutableSubset() {
        var profile = Profile.smoke(exact(A, B), new Budget(1, 3, 60, Overflow.FAIL));
        var decision = select(profile, ELIGIBLE, Set.of(), Map.of(A, 2d, B, 2d));
        assertEquals(Status.FAILED, decision.status());
        assertEquals(Set.of(), decision.selected());
        assertNull(decision.estimatedSeconds());
        assertTrue(decision.budgetExceeded());
        assertTrue(decision.reasons().contains(Reason.PROTECTED_BUDGET_EXCEEDED));
        assertEquals(Disposition.BLOCKED, decision.families().get(A).disposition());
        assertEquals(Disposition.NOT_SELECTED, decision.families().get(C).disposition());
    }

    @Test
    void explicitKeepRequiredOverflowRetainsEveryProtectedTestAndDefersOptionalWork() {
        var profile = Profile.affected(exact(A, B), List.of(rule("extra", Action.OPTIONAL, exact(C), "src/**")),
                new Budget(1, 1, 60, Overflow.KEEP_REQUIRED));
        var decision = select(profile, ELIGIBLE, Set.of("src/service.java"), Map.of(A, 2d, B, 3d, C, 0d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(5d, decision.estimatedSeconds());
        assertTrue(decision.budgetExceeded());
        assertEquals(Disposition.DEFERRED, decision.families().get(C).disposition());
    }

    @Test
    void optionalPriorityThenCostThenFamilyNameIsDeterministic() {
        var high = new Rule("high", Set.of("src/**"), Action.OPTIONAL, exact(C), 0);
        var low = new Rule("low", Set.of("src/**"), Action.OPTIONAL, exact(A, B), 1);
        var decision = select(Profile.affected(Targets.none(), List.of(low, high), new Budget(2, 10, 60, Overflow.FAIL)),
                ELIGIBLE, Set.of("src/service.java"), Map.of(A, 1d, B, 1d, C, 5d));
        assertEquals(Set.of(C, A), decision.selected());
        assertEquals(Disposition.DEFERRED, decision.families().get(B).disposition());
        assertEquals(6d, decision.estimatedSeconds());
    }

    @Test
    void decimalBudgetBoundaryFitsPointOnePlusPointTwoExactly() {
        var profile = Profile.affected(Targets.none(), List.of(rule("all", Action.OPTIONAL, exact(A, B), "src/**")),
                new Budget(0, .3, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(A, B), Set.of("src/service.java"), Map.of(A, .1, B, .2));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(.3, decision.estimatedSeconds());
        assertFalse(decision.budgetExceeded());
    }

    @Test
    void unmeasuredFamiliesUseConfiguredCostOnceAndAreVisibleWhenDeferred() {
        var profile = Profile.affected(exact(A), List.of(rule("extra", Action.OPTIONAL, exact(B), "src/**")),
                new Budget(0, 10, 8, Overflow.FAIL));
        var decision = select(profile, Set.of(A, B, B + "$Nested"), Set.of("src/service.java"), Map.of(A, 3d));
        assertEquals(Set.of(A), decision.selected());
        var extra = decision.families().get(B);
        assertEquals(8d, extra.estimatedSeconds());
        assertFalse(extra.measured());
        assertTrue(extra.reasons().containsAll(Set.of(Reason.UNKNOWN_ESTIMATE, Reason.TIME_BUDGET)));
        assertEquals(Disposition.DEFERRED, extra.disposition());
    }

    @Test
    void emptyAutomaticResultFollowsDeclaredFullOrFailPolicy() {
        var rule = rule("docs", Action.IGNORE, Targets.none(), "**/*.md");
        var full = Profile.affected(Targets.none(), List.of(rule), Budget.unlimited());
        assertEquals(ELIGIBLE, select(full, ELIGIBLE, Set.of("README.md"), Map.of()).selected());
        var fail = new Profile(Mode.AFFECTED, Targets.none(), List.of(rule), Budget.unlimited(),
                Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        var decision = select(fail, ELIGIBLE, Set.of("README.md"), Map.of());
        assertEquals(Status.FAILED, decision.status());
        assertTrue(decision.reasons().contains(Reason.EMPTY_SELECTION));
        assertEquals(Set.of("docs"), decision.matchedRules());
    }

    @Test
    void unmatchedChangesHaveExplicitFullOrFailOutcomes() {
        var full = Profile.affected(exact(A), List.of(), new Budget(1, 1, 60, Overflow.FAIL));
        var decision = select(full, ELIGIBLE, Set.of("unmapped/file.txt"), Map.of());
        assertEquals(Kind.FULL_FALLBACK, decision.kind());
        assertEquals(ELIGIBLE, decision.selected());
        assertTrue(decision.budgetExceeded());
        assertEquals(Set.of("unmapped/file.txt"), decision.unmatchedPaths());
        var fail = new Profile(Mode.AFFECTED, exact(A), List.of(), Budget.unlimited(), Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        assertEquals(Set.of(Reason.UNMATCHED_CHANGE), select(fail, ELIGIBLE, Set.of("unmapped/file.txt"), Map.of()).reasons());
    }

    @Test
    void unknownAcquisitionIsDifferentFromObservedEmptyDiff() {
        var profile = Profile.affected(exact(A), List.of(), Budget.unlimited());
        var policy = new SelectionPolicy(1, Map.of(PROFILE, profile));
        var unknown = SelectionEngine.select(policy, new SelectionContext(PROFILE, ELIGIBLE,
                SelectionContext.ChangeSet.unknown(), Map.of(), Set.of()));
        assertEquals(Kind.FULL_FALLBACK, unknown.kind());
        assertTrue(unknown.reasons().contains(Reason.UNKNOWN_CHANGES));
        assertEquals(Set.of(A), select(profile, ELIGIBLE, Set.of(), Map.of()).selected());
        var fail = new Profile(Mode.AFFECTED, exact(A), List.of(), Budget.unlimited(), Fallback.FAIL, Fallback.FULL, Fallback.FULL);
        assertEquals(Status.FAILED, SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, fail)),
                new SelectionContext(PROFILE, ELIGIBLE, SelectionContext.ChangeSet.unknown(), Map.of(), Set.of())).status());
    }

    @Test
    void explicitFullRuleTakesPrecedenceOverUnmatchedPathPolicy() {
        var profile = new Profile(Mode.AFFECTED, exact(A), List.of(rule("build", Action.FULL, Targets.none(), "build.gradle")),
                new Budget(1, 1, 60, Overflow.FAIL), Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        var decision = select(profile, ELIGIBLE, Set.of("build.gradle", "unknown.txt"), Map.of());
        assertEquals(Status.PASSED, decision.status());
        assertEquals(ELIGIBLE, decision.selected());
        assertEquals(Set.of("unknown.txt"), decision.unmatchedPaths());
        assertTrue(decision.families().get(B).rules().contains("build"));
        assertTrue(decision.reasons().contains(Reason.FULL_RULE));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void eachProfileHasAnIndependentMeaning(Mode mode) {
        var profile = new Profile(mode, exact(A), List.of(), Budget.unlimited(), Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        var context = new SelectionContext(PROFILE, ELIGIBLE, SelectionContext.ChangeSet.known(Set.of()), Map.of(),
                mode == Mode.EXPLICIT ? Set.of(B) : Set.of());
        var decision = SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, profile)), context);
        assertEquals(Status.PASSED, decision.status());
        assertEquals(mode == Mode.FULL ? ELIGIBLE : mode == Mode.EXPLICIT ? Set.of(A, B) : Set.of(A), decision.selected());
    }

    @Test
    void invalidTargetsFailEvenWhenTheirRuleDoesNotMatchThisDiff() {
        var profile = Profile.affected(exact(A), List.of(rule("stale", Action.OPTIONAL, exact("example.RemovedIT"), "other/**")), Budget.unlimited());
        var decision = select(profile, ELIGIBLE, Set.of(), Map.of());
        assertEquals(Status.FAILED, decision.status());
        assertEquals(Set.of("example.RemovedIT"), decision.invalidTargets());
        assertTrue(decision.selected().isEmpty());
        var pattern = Profile.smoke(new Targets(Set.of(), Set.of("missing.**")), Budget.unlimited());
        assertEquals(Set.of("missing.**"), select(pattern, ELIGIBLE, Set.of(), Map.of()).invalidTargets());
    }

    @Test
    void classPatternsMatchDefaultPackageUnicodeAndNestedEligibleMembers() {
        String unicode = "example.ŻółćIT";
        var profile = Profile.smoke(new Targets(Set.of(), Set.of("*IT", "example.?ółćIT")), Budget.unlimited());
        var decision = select(profile, Set.of("DefaultIT", unicode, unicode + "$Inner", B), Set.of(), Map.of());
        assertEquals(Set.of("DefaultIT", unicode, unicode + "$Inner"), decision.selected());
        assertFalse(decision.selected().contains(B));
    }

    @Test
    void invalidExplicitInputNeverFallsBackToAFullSuccessfulRun() {
        var policy = new SelectionPolicy(1, Map.of(PROFILE, Profile.explicit(Targets.none(), Budget.unlimited())));
        for (Set<String> request : List.of(Set.<String>of(), Set.of("example.ForeignIT"))) {
            var decision = SelectionEngine.select(policy, new SelectionContext(PROFILE, ELIGIBLE,
                    SelectionContext.ChangeSet.unknown(), Map.of(), request));
            assertEquals(Status.FAILED, decision.status());
            assertTrue(decision.reasons().contains(Reason.INVALID_EXPLICIT_REQUEST));
            assertTrue(decision.selected().isEmpty());
        }
        var full = new SelectionPolicy(1, Map.of(PROFILE, Profile.full()));
        assertEquals(Status.FAILED, SelectionEngine.select(full, new SelectionContext(PROFILE, ELIGIBLE,
                SelectionContext.ChangeSet.unknown(), Map.of(), Set.of(A))).status());
    }

    @Test
    void missingProfileAndEmptyEligibilityCannotPass() {
        var policy = new SelectionPolicy(1, Map.of("other-testdata", Profile.full()));
        var context = new SelectionContext(PROFILE, ELIGIBLE, SelectionContext.ChangeSet.unknown(), Map.of(), Set.of());
        assertEquals(Set.of(Reason.UNKNOWN_PROFILE), SelectionEngine.select(policy, context).reasons());
        assertEquals(Set.of(Reason.NO_ELIGIBLE_TESTS), select(Profile.full(), Set.of(), Set.of(), Map.of()).reasons());
    }

    @Test
    void overflowInFamilyOrAcceptedTotalFailsWithoutNonfiniteReportNumbers() {
        var family = select(Profile.full(), Set.of(A), Set.of(), Map.of(A, Double.MAX_VALUE, A + "$Inner", Double.MAX_VALUE));
        assertEquals(Status.FAILED, family.status());
        var total = select(Profile.full(), Set.of(A, B), Set.of(), Map.of(A, Double.MAX_VALUE, B, Double.MAX_VALUE));
        assertEquals(Status.FAILED, total.status());
        assertTrue(total.reasons().contains(Reason.DURATION_OVERFLOW));
        assertNull(total.estimatedSeconds());
        assertFalse(total.json().contains("Infinity"));
    }

    @Test
    void requiredRuleWinsOverOptionalProposalForTheSameFamilyWithoutDoubleCharging() {
        var profile = Profile.affected(Targets.none(), List.of(
                rule("a-probe", Action.OPTIONAL, exact(A), "src/**"),
                rule("z-required", Action.REQUIRE, exact(A + "$Nested"), "src/**")),
                new Budget(1, 5, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(A, A + "$Nested", B), Set.of("src/Service.java"), Map.of(A, 2d, A + "$Nested", 3d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Set.of(A, A + "$Nested"), decision.selected());
        assertEquals(5d, decision.estimatedSeconds());
        var family = decision.families().get(A);
        assertNotNull(family);
        assertTrue(family.protectedTarget());
        assertEquals(Set.of("a-probe", "z-required"), family.rules());
        assertTrue(family.reasons().containsAll(Set.of(Reason.OPTIONAL_RULE, Reason.REQUIRED_RULE)));
    }

    @Test
    void overlappingOptionalRulesUseStrongestPriorityRegardlessOfRuleIdOrder() {
        var profile = Profile.affected(Targets.none(), List.of(
                new Rule("a-low", Set.of("src/**"), Action.OPTIONAL, exact(B), 20),
                new Rule("m-other", Set.of("src/**"), Action.OPTIONAL, exact(A), 10),
                new Rule("z-high", Set.of("src/**"), Action.OPTIONAL, exact(B), 0)),
                new Budget(1, 0, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(A, B), Set.of("src/Service.java"), Map.of(A, 1d, B, 10d));
        assertEquals(Set.of(B), decision.selected());
        var family = decision.families().get(B);
        assertNotNull(family);
        assertEquals(Set.of("a-low", "z-high"), family.rules());
    }

    @Test
    void oversizedHighPriorityProbeDoesNotConsumeBudgetNeededByALaterProbe() {
        var profile = Profile.affected(exact(A), List.of(
                new Rule("expensive", Set.of("src/**"), Action.OPTIONAL, exact(B), 0),
                new Rule("cheap", Set.of("src/**"), Action.OPTIONAL, exact(C), 1)),
                new Budget(2, 5, 60, Overflow.FAIL));
        var decision = select(profile, ELIGIBLE, Set.of("src/Service.java"), Map.of(A, 2d, B, 8d, C, 3d));
        assertEquals(Set.of(A, C), decision.selected());
        assertEquals(5d, decision.estimatedSeconds());
        var deferred = decision.families().get(B);
        assertNotNull(deferred);
        assertEquals(Disposition.DEFERRED, deferred.disposition());
        assertTrue(deferred.reasons().contains(Reason.TIME_BUDGET));
        assertFalse(decision.budgetExceeded());
    }

    @Test
    void measuredZeroCostDoesNotReceiveTheUnknownFamilySubstitute() {
        var profile = Profile.smoke(exact(A, B), new Budget(2, 1, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(A, B), Set.of(), Map.of(A, 0d, B, 1d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(1d, decision.estimatedSeconds());
        var zero = decision.families().get(A);
        assertNotNull(zero);
        assertTrue(zero.measured());
        assertEquals(0d, zero.estimatedSeconds());
        assertFalse(zero.reasons().contains(Reason.UNKNOWN_ESTIMATE));
    }

    @Test
    void bothSidesOfAnExternallyObservedRenameContributeRequiredTests() {
        // The core receives both paths. It must not discard the deleted/old side of an adapter's diff.
        var profile = Profile.affected(Targets.none(), List.of(
                rule("old-area", Action.REQUIRE, exact(A), "src/old/**"),
                rule("new-area", Action.REQUIRE, exact(B), "src/new/**")), Budget.unlimited());
        var decision = select(profile, ELIGIBLE, Set.of("src/old/Service.java", "src/new/Service.java"), Map.of());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(Set.of("old-area", "new-area"), decision.matchedRules());
        assertTrue(decision.unmatchedPaths().isEmpty());
    }

    @Test
    void fullProfileReportsLimitsWithoutTruncatingCoverage() {
        var profile = new Profile(Mode.FULL, Targets.none(), List.of(), new Budget(1, 1, 60, Overflow.FAIL),
                Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        var decision = select(profile, ELIGIBLE, Set.of("unclassified.txt"), Map.of(A, 2d, B, 3d, C, 4d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Kind.FULL, decision.kind());
        assertEquals(ELIGIBLE, decision.selected());
        assertTrue(decision.budgetExceeded());
        assertEquals(9d, decision.estimatedSeconds());
        assertEquals(Set.of(Reason.FULL_PROFILE), decision.reasons());
    }

    @Test
    void optionalOnlyEmptyBudgetResultUsesObservableFullFallback() {
        var profile = Profile.affected(Targets.none(), List.of(rule("probe", Action.OPTIONAL, exact(A), "src/**")),
                new Budget(1, 1, 60, Overflow.FAIL));
        var decision = select(profile, Set.of(A, B), Set.of("src/Service.java"), Map.of(A, 5d, B, 2d));
        assertEquals(Status.PASSED, decision.status());
        assertEquals(Kind.FULL_FALLBACK, decision.kind());
        assertEquals(Set.of(A, B), decision.selected());
        assertEquals(Set.of(Reason.EMPTY_SELECTION), decision.reasons());
        assertTrue(decision.budgetExceeded());
    }

    private static Targets exact(String... names) {
        return new Targets(Set.of(names), Set.of());
    }

    private static Rule rule(String id, Action action, Targets targets, String path) {
        return new Rule(id, Set.of(path), action, targets, 0);
    }

    private static SelectionDecision select(Profile profile, Set<String> eligible, Set<String> paths, Map<String, Double> history) {
        return SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, profile)), new SelectionContext(PROFILE,
                eligible, SelectionContext.ChangeSet.known(paths), history, Set.of()));
    }
}
