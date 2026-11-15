package io.github.sjacek.shardpilot;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.SelectionDecision.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same policy drives both adapters; protected impact can never be silently deferred by a budget. */
class SelectionImpactTest {
    private static final Set<String> E = Set.of("example.AlphaTest", "example.AlphaTest$Nested", "example.BetaTest", "example.GammaTest", "example.UnrelatedTest");

    @Test
    void changedEligibleSourcesProtectTheWholeFamilyWithoutIgnoreVeto() {
        var policy = policy("""
                changedTests: [{path: src/test/java}]
                """);
        var result = select(policy, Set.of("src/test/java/example/AlphaTest.java"), Map.of());
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested"), result.selected());
        assertTrue(result.families().get("example.AlphaTest").reasons().contains(CHANGED_TEST));
        assertTrue(result.families().get("example.AlphaTest").protectedTarget());
        assertTrue(result.matchedRules().contains("changed-tests:src/test/java"));
    }

    @Test
    void moduleChangesProtectTransitiveReverseDependentsAndNotProviders() {
        var p = policy("""
                modules:
                  - {id: library, changes: ['library/**'], tests: {classes: [example.AlphaTest]}}
                  - {id: api, changes: ['api/**'], dependsOn: [library], tests: {classes: [example.BetaTest]}}
                  - {id: app, changes: ['app/**'], dependsOn: [api], tests: {classes: [example.GammaTest]}}
                """);
        var result = select(p, Set.of("library/Value.java"), Map.of());
        assertEquals(4, result.selected().size()); assertFalse(result.selected().contains("example.UnrelatedTest"));
        assertTrue(result.families().get("example.GammaTest").reasons().contains(DEPENDENT_MODULE));
        assertEquals(Set.of("example.GammaTest"), select(p, Set.of("app/Consumer.java"), Map.of()).selected());
    }

    @Test
    void sourcePrefixExtensionsAndDeletedHelpersRespectEligibilityAndFallback() {
        var p = policy("changedTests: [{path: tests, packagePrefix: example, extensions: [groovy]}]");
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested"), select(p, Set.of("tests/AlphaTest.groovy"), Map.of()).selected());
        // No source parser or deleted bytecode is invented. Without another rule, unknown mappings fail.
        var original = p.profiles().get("affected-testdata");
        var strict = new SelectionPolicy(2, Map.of("affected-testdata", new SelectionPolicy.Profile(original.mode(), original.mandatory(),
                java.util.List.of(), original.budget(), original.onUnknownChanges(), SelectionPolicy.Fallback.FAIL,
                original.onEmptySelection(), original.impact(), original.onUnknownImpact())));
        for (String path : Set.of("tests/DeletedTest.groovy", "tests/AlphaTest.java", "tests/helpers/Fixture.groovy")) {
            var result = select(strict, Set.of(path), Map.of());
            assertEquals(SelectionDecision.Status.FAILED, result.status()); assertTrue(result.reasons().contains(UNMATCHED_CHANGE));
        }
    }

    @Test
    void diamondDependenciesAndOverlappingRootsReserveEachFamilyOnlyOnce() {
        var p = policy("""
                modules:
                  - {id: base, changes: ['base/**']}
                  - {id: left, changes: ['consumer/**'], dependsOn: [base]}
                  - {id: right, changes: ['consumer/**'], dependsOn: [base]}
                  - {id: app, changes: ['app/**'], dependsOn: [left, right], tests: {classes: [example.BetaTest]}}
                """);
        var result = select(p, Set.of("base/Value.java", "consumer/Value.java"), Map.of());
        assertEquals(Set.of("example.BetaTest"), result.selected());
        assertEquals(1, result.families().values().stream().filter(SelectionDecision.Family::protectedTarget).count());
        assertTrue(result.matchedRules().containsAll(Set.of("module:base", "module:left", "module:right", "module:app")));
    }

    @Test
    void scopedContractsSelectOnlyRelevantTargetsAndUncoveredChangesCannotBeIgnored() {
        var p = policy(contract());
        var known = diff("{\"api\":1,\"meta\":0}", "{\"api\":2,\"meta\":0}");
        var result = select(p, Set.of("contracts/api.json"), Map.of("contracts/api.json", known));
        assertEquals(Set.of("example.BetaTest"), result.selected());
        assertTrue(result.families().get("example.BetaTest").reasons().contains(REQUIRED_CONTRACT));
        var unknown = select(p, Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"meta\":0}", "{\"meta\":1}")));
        assertEquals(E, unknown.selected()); assertTrue(unknown.reasons().contains(UNKNOWN_IMPACT));
        assertTrue(select(p, Set.of("contracts/api.json"), Map.of()).reasons().contains(UNKNOWN_IMPACT));
    }

    @Test
    void impactRequiredTestsAreNeverDroppedToFitTheBudget() {
        var p = policy("changedTests: [{path: src/test/java}]\nmodules: [{id: app, changes: ['app/**'], tests: {classes: [example.BetaTest]}}]");
        String yaml = SelectionPolicyYaml.format(p).replace("maxFamilies: 0", "maxFamilies: 1");
        var result = select(SelectionPolicyYaml.parse(yaml), Set.of("src/test/java/example/AlphaTest.java", "app/Value.java"), Map.of());
        assertEquals(SelectionDecision.Status.FAILED, result.status()); assertTrue(result.reasons().contains(PROTECTED_BUDGET_EXCEEDED));
        assertTrue(result.selected().isEmpty());
    }

    @Test
    void optionalContractsRespectPriorityAndBudgetWithoutDisplacingMandatoryTests() {
        String yaml = SelectionPolicyYaml.format(policy("""
                contracts:
                  - {id: early, files: ['contracts/*.json'], format: json, pointers: ['/api'], action: optional, priority: 1, tests: {classes: [example.BetaTest]}}
                  - {id: later, files: ['contracts/*.json'], format: json, pointers: ['/api'], action: optional, priority: 2, tests: {classes: [example.GammaTest]}}
                """));
        yaml = yaml.replace("maxFamilies: 0", "maxFamilies: 2").replace("mandatory:\n      classes: []", "mandatory:\n      classes: [example.AlphaTest]");
        var result = select(SelectionPolicyYaml.parse(yaml), Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"api\":1}", "{\"api\":2}")));
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested", "example.BetaTest"), result.selected());
        assertEquals(SelectionDecision.Disposition.DEFERRED, result.families().get("example.GammaTest").disposition());
        assertTrue(result.families().get("example.BetaTest").reasons().contains(OPTIONAL_CONTRACT));
    }

    @Test
    void replacingAParentCannotClaimCoverageOfUnknownSiblingsThroughAChildPointer() {
        var removed = StructuredContracts.compare(SelectionImpact.Format.JSON, "{\"api\":1,\"other\":2}".getBytes(StandardCharsets.UTF_8), null);
        var result = select(policy(contract()), Set.of("contracts/api.json"), Map.of("contracts/api.json", removed));
        assertEquals(E, result.selected()); assertTrue(result.reasons().contains(UNKNOWN_IMPACT));
    }

    @Test
    void incompatibleSnapshotFormatDoesNotNarrowContractImpact() {
        var yaml = StructuredContracts.compare(SelectionImpact.Format.YAML, "api: 1".getBytes(StandardCharsets.UTF_8), "api: 2".getBytes(StandardCharsets.UTF_8));
        var result = select(policy(contract()), Set.of("contracts/api.json"), Map.of("contracts/api.json", yaml));
        assertEquals(E, result.selected()); assertTrue(result.reasons().contains(UNKNOWN_IMPACT));
    }

    @Test
    void unknownImpactCanFailAndAFullRuleRemainsASafeOverride() {
        var p = SelectionPolicyYaml.parse(SelectionPolicyYaml.format(policy(contract())).replace("onUnknownImpact: full", "onUnknownImpact: fail"));
        assertEquals(SelectionDecision.Status.FAILED, select(p, Set.of("contracts/api.json"), Map.of()).status());
        var full = SelectionPolicyYaml.parse(SelectionPolicyYaml.format(p).replace("action: ignore", "action: full"));
        assertEquals(E, select(full, Set.of("contracts/api.json"), Map.of()).selected());
    }

    @Test
    void policyAndStructuredByteChangesInvalidateIdentityEvenForEqualSelections() {
        var p = policy(contract());
        assertEquals(p, SelectionPolicyYaml.parse(SelectionPolicyYaml.format(p)));
        var a = select(p, Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"api\":1}", "{\"api\":2}")));
        var b = select(p, Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"api\":1}", "{ \"api\":2 }")));
        assertEquals(a.selected(), b.selected()); assertNotEquals(a.identity(), b.identity());
        assertFalse(a.json().contains("\"api\":2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "modules: [{id: a, changes: ['a/**'], dependsOn: [missing]}]",
            "modules: [{id: a, changes: ['a/**'], dependsOn: [b]}, {id: b, changes: ['b/**'], dependsOn: [a]}]",
            "changedTests: [{path: '../escape'}]",
            "changedTests: [{path: src/test/java}, {path: src/test/java}]",
            "contracts: [{id: api, files: ['api.json'], format: json, pointers: ['/bad~2'], action: full}]",
            "contracts: [{id: api, files: ['api.json'], format: json, pointers: ['/api'], action: require}]"
    })
    void rejectsInvalidGraphsMappingsAndContractRulesBeforeAcquisition(String impact) {
        assertThrows(IllegalArgumentException.class, () -> policy(impact));
    }

    @Test
    void staleInactiveTargetsStillFailAndV1RejectsTheNewFields() {
        assertEquals(SelectionDecision.Status.FAILED, select(policy("modules: [{id: x, changes: ['x/**'], tests: {classes: [example.MissingTest]}}]"), Set.of("docs/readme.md"), Map.of()).status());
        assertThrows(IllegalArgumentException.class, () -> SelectionPolicyYaml.parse(SelectionPolicyYaml.format(policy(contract())).replace("schemaVersion: 2", "schemaVersion: 1")));
    }

    @Test
    void aSemanticNoOpKeepsOnlyMandatoryTestsAndDoesNotActivateContractTargets() {
        var original = policy(contract()).profiles().get("affected-testdata");
        var mandatory = new SelectionPolicy.Targets(Set.of("example.AlphaTest"), Set.of());
        var configured = new SelectionPolicy(2, Map.of("affected-testdata", new SelectionPolicy.Profile(original.mode(), mandatory,
                original.rules(), original.budget(), original.onUnknownChanges(), original.onUnmatchedChanges(),
                original.onEmptySelection(), original.impact(), original.onUnknownImpact())));
        var result = select(configured, Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"api\":1}", "{ \"api\":1.0 }")));
        assertEquals(SelectionDecision.Status.PASSED, result.status());
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested"), result.selected());
        assertFalse(result.matchedRules().contains("contract:api"));
        assertFalse(result.reasons().contains(UNKNOWN_IMPACT));
    }

    @Test
    void aRootIgnoreClassifiesOtherFieldsWithoutVetoingAnOverlappingRequiredContract() {
        var configured = policy("""
                contracts:
                  - {id: api, files: ['contracts/*.json'], format: json, pointers: ['/api'], action: require, tests: {classes: [example.BetaTest]}}
                  - {id: descriptions, files: ['contracts/*.json'], format: json, pointers: [''], action: ignore}
                """);
        var result = select(configured, Set.of("contracts/api.json"), Map.of("contracts/api.json", diff("{\"api\":1,\"description\":1}", "{\"api\":2,\"description\":2}")));
        assertEquals(Set.of("example.BetaTest"), result.selected());
        assertTrue(result.matchedRules().containsAll(Set.of("contract:api", "contract:descriptions")));
        assertFalse(result.reasons().contains(UNKNOWN_IMPACT));
    }

    @Test
    void legacyProfileConstructorRetainsV1CanonicalTransportWhileV2IsAnExplicitOptIn() {
        var legacy = new SelectionPolicy.Profile(SelectionPolicy.Mode.AFFECTED, SelectionPolicy.Targets.none(), java.util.List.of(),
                SelectionPolicy.Budget.unlimited(), SelectionPolicy.Fallback.FULL, SelectionPolicy.Fallback.FULL, SelectionPolicy.Fallback.FULL);
        var v1 = new SelectionPolicy(1, Map.of("affected-testdata", legacy));
        String canonical = SelectionPolicyYaml.format(v1);
        assertFalse(canonical.contains("impact:"));
        assertFalse(canonical.contains("onUnknownImpact:"));
        assertEquals(v1, SelectionPolicyYaml.parse(canonical));
        var v2 = new SelectionPolicy(2, v1.profiles());
        assertEquals(v2, SelectionPolicyYaml.parse(SelectionPolicyYaml.format(v2)));
        assertEquals(select(v1, Set.of(), Map.of()).selected(), select(v2, Set.of(), Map.of()).selected());
        assertNotEquals(select(v1, Set.of(), Map.of()).identity(), select(v2, Set.of(), Map.of()).identity());
    }

    @Test
    void callerCollectionChangesCannotRewriteAnAcceptedConfigurationOrObservation() {
        var paths = new java.util.TreeSet<>(Set.of("/api"));
        var change = new SelectionImpact.Change(SelectionImpact.Format.JSON, SelectionImpact.Failure.NONE, paths, "a".repeat(64), "b".repeat(64));
        var roots = new java.util.ArrayList<>(java.util.List.of(new SelectionImpact.SourceRoot("src/test/java", "", Set.of("java"))));
        var config = new SelectionImpact.Configuration(roots, java.util.List.of(), java.util.List.of());
        paths.clear();
        roots.clear();
        assertEquals(Set.of("/api"), change.pointers());
        assertEquals(1, config.changedTests().size());
        assertFalse(config.isEmpty());
    }

    private static String contract() { return "contracts: [{id: api, files: ['contracts/*.json'], format: json, pointers: ['/api'], action: require, tests: {classes: [example.BetaTest]}}]"; }

    private static SelectionPolicy policy(String impact) {
        return SelectionPolicyYaml.parse("schemaVersion: 2\nprofiles:\n  affected-testdata:\n    mode: affected\n    onEmptySelection: fail\n    rules: [{id: classify, changes: ['**'], action: ignore}]\n    impact:\n" + impact.indent(6));
    }

    private static SelectionDecision select(SelectionPolicy p, Set<String> paths, Map<String, SelectionImpact.Change> changes) {
        return SelectionEngine.select(p, new SelectionContext("affected-testdata", E,
                new SelectionContext.ChangeSet(SelectionContext.Availability.KNOWN, paths, "", "", changes), Map.of(), Set.of()));
    }

    private static SelectionImpact.Change diff(String a, String b) { return StructuredContracts.compare(SelectionImpact.Format.JSON, a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
}
