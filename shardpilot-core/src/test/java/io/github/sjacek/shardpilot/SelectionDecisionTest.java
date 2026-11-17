package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import static io.github.sjacek.shardpilot.SelectionContext.*;
import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Planning evidence must be stable, safe to render and consumable by the existing manifest boundary. */
class SelectionDecisionTest {
    private static final String PROFILE = "daily-testdata";
    private static final String A = "example.AlphaIT";
    private static final String B = "example.BetaIT";
    private static final Targets REQUIRED = new Targets(Set.of(A), Set.of());

    @TempDir
    Path directory;

    @Test
    void identicalInputsHaveByteIdenticalReportsDespiteCollectionOrderAndConcurrentEvaluation() {
        var rules = List.of(new Rule("extra", Set.of("src/**"), Action.OPTIONAL, new Targets(Set.of(B), Set.of()), 0));
        var profile = Profile.affected(REQUIRED, rules, new Budget(1, 0, 60, Overflow.FAIL));
        var policy = new SelectionPolicy(1, Map.of(PROFILE, profile));
        var context = context(Set.of(A, B), ChangeSet.known(Set.of("src/b.java", "src/a.java")), Map.of(A, 1d, B, 2d));
        var reverse = context(new LinkedHashSet<>(List.of(B, A)), ChangeSet.known(new LinkedHashSet<>(List.of("src/a.java", "src/b.java"))),
                new LinkedHashMap<>(Map.of(B, 2d, A, 1d)));
        var expected = SelectionEngine.select(policy, context);
        assertEquals(expected.json(), SelectionEngine.select(policy, reverse).json());
        // The JVM owns the shared pool. Await all tasks before assertions so no work outlives a failed assertion.
        var calls = new ArrayList<CompletableFuture<String>>();
        for (int index = 0; index < 20; index++) calls.add(CompletableFuture.supplyAsync(() -> SelectionEngine.select(policy, context).json()));
        CompletableFuture.allOf(calls.toArray(CompletableFuture<?>[]::new)).join();
        for (var result : calls) assertEquals(expected.json(), result.join());
        // Mutation is intentional here: these assertions verify the public immutable snapshot contract.
        //noinspection DataFlowIssue
        assertThrows(UnsupportedOperationException.class, () -> expected.selected().clear());
        //noinspection DataFlowIssue
        assertThrows(UnsupportedOperationException.class, () -> expected.families().clear());
        var family = expected.families().get(A);
        assertNotNull(family);
        assertThrows(UnsupportedOperationException.class, () -> family.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> family.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> family.reasons().clear());
    }

    @Test
    void identityBindsPolicyEligibilityHistoryChangesRevisionsProfileAndExplicitRequest() {
        var profile = Profile.smoke(REQUIRED, Budget.unlimited());
        var policy = new SelectionPolicy(1, Map.of(PROFILE, profile, "other-testdata", profile));
        var baseline = context(Set.of(A, B), ChangeSet.known(Set.of("src/a.java")), Map.of(A, 1d));
        String identity = SelectionEngine.select(policy, baseline).identity();
        var variants = List.of(
                context(Set.of(A), baseline.changes(), baseline.history()),
                context(baseline.eligible(), ChangeSet.known(Set.of("src/b.java")), baseline.history()),
                context(baseline.eligible(), ChangeSet.unknown(), baseline.history()),
                context(baseline.eligible(), new ChangeSet(Availability.KNOWN, baseline.changes().paths(), "a".repeat(40), "b".repeat(40)), baseline.history()),
                context(baseline.eligible(), baseline.changes(), Map.of(A, 1d, "foreign.History", 2d)),
                new SelectionContext("other-testdata", baseline.eligible(), baseline.changes(), baseline.history(), Set.of()),
                new SelectionContext(PROFILE, baseline.eligible(), baseline.changes(), baseline.history(), Set.of(B)));
        for (var variant : variants) assertNotEquals(identity, SelectionEngine.select(policy, variant).identity());
        var changed = new SelectionPolicy(1, Map.of(PROFILE, Profile.smoke(REQUIRED, new Budget(0, 0, 61, Overflow.FAIL)), "other-testdata", profile));
        assertEquals(Set.of(A), SelectionEngine.select(changed, baseline).selected());
        assertNotEquals(identity, SelectionEngine.select(changed, baseline).identity());
        assertTrue(identity.matches("[0-9a-f]{64}"));
    }

    @Test
    void outputBridgesToExistingExplicitSelectionAndCompleteNonoverlappingShardOwnership() throws Exception {
        var context = context(Set.of(A, A + "$Nested", B), ChangeSet.unknown(), Map.of(A, 1d, B, 2d));
        var decision = SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, Profile.smoke(REQUIRED, Budget.unlimited()))), context);
        decision.write(directory);
        assertEquals(decision.identity() + "\n", Files.readString(directory.resolve("decision.id")));
        assertEquals(decision.eligible(), ClassManifest.read(directory.resolve("eligible.yaml"), false));
        var selected = TestSelection.read(directory.resolve("selected.yaml"), context.eligible(), "policy-testdata");
        assertEquals(decision.selected(), selected.selected());
        for (int count : List.of(1, 2, 4)) {
            var plan = ShardPlan.create(TestSelection.history(A + "\t1\n" + B + "\t2\n", selected.selected()), count);
            var seen = new java.util.HashSet<String>();
            for (int index = 1; index <= count; index++) {
                for (String name : selected.selected()) {
                    if (plan.shardFor(name) == index) assertTrue(seen.add(name));
                }
            }
            assertEquals(selected.selected(), seen);
            assertEquals(plan.shardFor(A), plan.shardFor(A + "$Nested"));
        }
    }

    @Test
    void failedDecisionRemovesStaleExecutableArtifactsAndRetainsDiagnosticsAndUnrelatedFiles() throws Exception {
        var policy = new SelectionPolicy(1, Map.of(PROFILE, Profile.smoke(REQUIRED, Budget.unlimited())));
        var good = SelectionEngine.select(policy, context(Set.of(A), ChangeSet.unknown(), Map.of()));
        good.write(directory);
        Files.writeString(directory.resolve("maintained.txt"), "keep");
        var failed = SelectionEngine.select(policy, context(Set.of(B), ChangeSet.unknown(), Map.of()));
        failed.write(directory);
        assertFalse(Files.exists(directory.resolve("selected.yaml")));
        assertFalse(Files.exists(directory.resolve("decision.id")));
        assertEquals("keep", Files.readString(directory.resolve("maintained.txt")));
        assertEquals(failed.json(), Files.readString(directory.resolve("decision.json")));
        assertEquals(failed.html(), Files.readString(directory.resolve("decision.html")));
        assertEquals(Set.of(B), ClassManifest.read(directory.resolve("eligible.yaml"), false));
    }

    @Test
    void jsonIsStructuredAndHtmlEscapesUntrustedPathsWithoutExecutingThem() {
        String hostile = "docs/<script>alert('x') & \"y\"</script>.md";
        var profile = new Profile(Mode.AFFECTED, REQUIRED, List.of(), Budget.unlimited(), Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        var decision = SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, profile)),
                context(Set.of(A), ChangeSet.known(Set.of(hostile)), Map.of(A, 1d)));
        Object parsed = new Load(LoadSettings.builder().build()).loadFromString(decision.json());
        assertInstanceOf(Map.class, parsed);
        var report = (Map<?, ?>) parsed;
        assertEquals("FAILED", report.get("status"));
        assertEquals(List.of(hostile), report.get("unmatchedPaths"));
        assertNull(report.get("estimatedSeconds"));
        assertEquals(decision.identity(), report.get("identity"));
        assertFalse(decision.html().contains("<script>"));
        assertTrue(decision.html().contains("&lt;script&gt;"));
        assertTrue(decision.html().contains("&amp;"));
    }

    @Test
    void identityNormalizesEditorialChangesButBindsUnusedProfileSemantics() {
        var first = SelectionPolicyYaml.parse("""
                schemaVersion: 1
                profiles:
                  daily-testdata: {mode: smoke, mandatory: {classes: [example.AlphaIT]}}
                  other-testdata: {mode: full}
                """);
        var reordered = SelectionPolicyYaml.parse("""
                # Review comments and map order do not alter policy semantics.
                profiles:
                  other-testdata: {mode: full}
                  daily-testdata: {mandatory: {classes: [example.AlphaIT]}, mode: smoke}
                schemaVersion: 1
                """);
        var context = context(Set.of(A), ChangeSet.unknown(), Map.of(A, 1d));
        var decision = SelectionEngine.select(first, context);
        assertEquals(decision.json(), SelectionEngine.select(reordered, context).json());
        var changed = new SelectionPolicy(1, Map.of(PROFILE, Profile.smoke(REQUIRED, Budget.unlimited()),
                "other-testdata", Profile.explicit(Targets.none(), Budget.unlimited())));
        var otherDecision = SelectionEngine.select(changed, context);
        assertEquals(decision.selected(), otherDecision.selected());
        assertNotEquals(decision.policySha256(), otherDecision.policySha256());
        assertNotEquals(decision.identity(), otherDecision.identity());
    }

    @Test
    void eachRevisionAndExistingTimingValueIndependentlyInvalidateIdentity() {
        var policy = new SelectionPolicy(1, Map.of(PROFILE, Profile.smoke(REQUIRED, Budget.unlimited())));
        var baseline = context(Set.of(A), new ChangeSet(Availability.KNOWN, Set.of(), "a".repeat(40), "b".repeat(40)), Map.of(A, 1d));
        var original = SelectionEngine.select(policy, baseline);
        var variants = List.of(
                context(baseline.eligible(), new ChangeSet(Availability.KNOWN, Set.of(), "c".repeat(40), "b".repeat(40)), baseline.history()),
                context(baseline.eligible(), new ChangeSet(Availability.KNOWN, Set.of(), "a".repeat(40), "c".repeat(40)), baseline.history()),
                context(baseline.eligible(), baseline.changes(), Map.of(A, 2d)));
        for (var variant : variants) {
            var changed = SelectionEngine.select(policy, variant);
            assertEquals(original.selected(), changed.selected());
            assertEquals(original.policySha256(), changed.policySha256());
            assertNotEquals(original.identity(), changed.identity());
        }
    }

    @Test
    void successfulJsonAgreesWithTheAcceptedManifestAndFamilyEvidence() throws Exception {
        var profile = Profile.affected(REQUIRED, List.of(new Rule("extra", Set.of("src/**"), Action.OPTIONAL,
                new Targets(Set.of(B), Set.of()), 0)), new Budget(1, 10, 60, Overflow.FAIL));
        var decision = SelectionEngine.select(new SelectionPolicy(1, Map.of(PROFILE, profile)),
                context(Set.of(A, A + "$Nested", B), ChangeSet.known(Set.of("src/Service.java")), Map.of(A, 2d, B, 3d)));
        decision.write(directory);
        var parsed = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(directory.resolve("decision.json")));
        Map<?, ?> report = assertInstanceOf(Map.class, parsed);
        assertEquals(1, report.get("schemaVersion"));
        assertEquals(1, report.get("engineVersion"));
        assertEquals("PASSED", report.get("status"));
        assertEquals(3, report.get("eligibleClasses"));
        assertEquals(2, report.get("selectedClasses"));
        assertEquals(1, report.get("selectedFamilies"));
        assertEquals(2d, report.get("estimatedSeconds"));
        assertEquals(Set.of(A, A + "$Nested"), ClassManifest.read(directory.resolve("selected.yaml"), false));
        List<?> families = assertInstanceOf(List.class, report.get("families"));
        Map<?, ?> required = assertInstanceOf(Map.class, families.get(0));
        assertEquals(A, required.get("family"));
        assertEquals(List.of(A, A + "$Nested"), required.get("members"));
        assertEquals("SELECTED", required.get("disposition"));
        assertEquals(true, required.get("protected"));
        Map<?, ?> optional = assertInstanceOf(Map.class, families.get(1));
        assertEquals("DEFERRED", optional.get("disposition"));
        assertEquals(List.of("FAMILY_BUDGET", "OPTIONAL_RULE"), optional.get("reasons"));
    }

    @Test
    void diagnosticIoFailureInvalidatesEarlierExecutableArtifacts() throws Exception {
        var policy = new SelectionPolicy(1, Map.of(PROFILE, Profile.full()));
        var decision = SelectionEngine.select(policy, context(Set.of(A), ChangeSet.unknown(), Map.of()));
        decision.write(directory);
        // Simulate an unwritable report target portably, without relying on OS-specific permissions.
        Files.delete(directory.resolve("decision.json"));
        Files.createDirectory(directory.resolve("decision.json"));
        Files.writeString(directory.resolve("decision.json/keep.txt"), "retained");
        assertThrows(IOException.class, () -> decision.write(directory));
        assertFalse(Files.exists(directory.resolve("selected.yaml")));
        assertFalse(Files.exists(directory.resolve("decision.id")));
        assertEquals("retained", Files.readString(directory.resolve("decision.json/keep.txt")));
    }

    @Test
    void aLaterSuccessfulDecisionReplacesAnEmptyEligibilityFailure() throws Exception {
        var policy = new SelectionPolicy(1, Map.of(PROFILE, Profile.full()));
        var rejected = SelectionEngine.select(policy, context(Set.of(), ChangeSet.unknown(), Map.of()));
        rejected.write(directory);
        assertEquals(Set.of(), ClassManifest.read(directory.resolve("eligible.yaml"), true));
        assertFalse(Files.exists(directory.resolve("selected.yaml")));
        var accepted = SelectionEngine.select(policy, context(Set.of(B), ChangeSet.unknown(), Map.of(B, 2d)));
        accepted.write(directory);
        assertEquals(Set.of(B), ClassManifest.read(directory.resolve("eligible.yaml"), false));
        assertEquals(Set.of(B), ClassManifest.read(directory.resolve("selected.yaml"), false));
        assertEquals(accepted.json(), Files.readString(directory.resolve("decision.json")));
        assertEquals(accepted.identity() + "\n", Files.readString(directory.resolve("decision.id")));
        assertNotEquals(rejected.identity(), accepted.identity());
    }

    private static SelectionContext context(Set<String> eligible, ChangeSet changes, Map<String, Double> history) {
        return new SelectionContext(PROFILE, eligible, changes, history, Set.of());
    }
}
