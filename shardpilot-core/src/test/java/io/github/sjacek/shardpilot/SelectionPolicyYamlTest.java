package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same strict wire parameters and immutable types can be consumed by either adapter. */
class SelectionPolicyYamlTest {
    @Test
    void documentedPortablePolicyRunsThroughThePublicContract() throws Exception {
        try (var input = SelectionPolicyYamlTest.class.getResourceAsStream("/selection-policy-testdata.yaml")) {
            assertNotNull(input);
            var policy = SelectionPolicyYaml.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var eligible = Set.of("example.LoginIT", "example.payments.TransferIT", "example.CompatibilityIT", "example.UnrelatedIT");
            var decision = SelectionEngine.select(policy, new SelectionContext("daily-testdata", eligible,
                    SelectionContext.ChangeSet.known(Set.of("src/payments/Transfer.java")),
                    Map.of("example.LoginIT", 5d, "example.payments.TransferIT", 25d, "example.CompatibilityIT", 80d), Set.of()));
            assertEquals(SelectionDecision.Status.PASSED, decision.status());
            assertEquals(Set.of("example.LoginIT", "example.payments.TransferIT"), decision.selected());
            assertEquals(SelectionDecision.Disposition.DEFERRED, decision.families().get("example.CompatibilityIT").disposition());
            assertEquals(policy, SelectionPolicyYaml.parse(SelectionPolicyYaml.format(policy)));
        }
    }

    @Test
    void boundsNestingBeforeRecursiveYamlComposition() {
        assertThrows(IllegalArgumentException.class, () -> SelectionPolicyYaml.parse("[".repeat(5_000) + "0" + "]".repeat(5_000)));
    }

    @Test
    void parsesProfilesRulesMandatoryTargetsAndExplicitBudgetDefaults() {
        String yaml = """
                schemaVersion: 1
                profiles:
                  daily-testdata:
                    mode: affected
                    mandatory: {classes: [example.CoreIT]}
                    rules:
                      - id: billing
                        changes: [src/billing/**]
                        action: optional
                        tests: {patterns: [example.billing.**]}
                        priority: 2
                    budget: {maxSeconds: 900, maxFamilies: 24, unknownSeconds: 45, overflow: keep-required}
                  full-testdata:
                    mode: full
                  smoke-testdata:
                    mode: smoke
                    mandatory: {patterns: [example.security.**]}
                  explicit-testdata:
                    mode: explicit
                """;
        var policy = SelectionPolicyYaml.parse(yaml);
        var daily = policy.profiles().get("daily-testdata");
        assertEquals(new Budget(24, 900, 45, Overflow.KEEP_REQUIRED), daily.budget());
        assertEquals(Fallback.FULL, daily.onUnmatchedChanges());
        assertEquals(Fallback.FAIL, policy.profiles().get("smoke-testdata").onEmptySelection());
        assertEquals(2, daily.rules().get(0).priority());
        assertEquals(policy, SelectionPolicyYaml.parse(SelectionPolicyYaml.format(policy)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: FULL}}",
            "schemaVersion: 1\nprofiles: {1: {mode: full}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {maxFamilies: '2'}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: affected, rules: [{id: x, changes: ['**'], action: optional, tests: {classes: [example.A]}, priority: 1.5}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: smoke, mandatory: {classes: [example.A], extra: secret-sentinel}}}",
            "schemaVersion: 2\nprofiles: {daily-testdata: {mode: full}}",
            "schemaVersion: 1\nprofiles: {}",
            "schemaVersion: 1.0\nprofiles: {daily-testdata: {mode: full}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, extra: secret-sentinel}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mode: smoke}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: unknown}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mandatory: {classes: [example.A]}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {maxSeconds: .nan}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {maxFamilies: -1}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {maxFamilies: 1.5}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {unknownSeconds: 0}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {unknownSeconds: .inf}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, budget: {overflow: ignore}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, onEmptySelection: skip}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mandatory: {classes: [example.A, example.A]}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mandatory: {classes: [true]}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mandatory: {classes: [bad/name]}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mandatory: {patterns: [example.**Bad]}}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, rules: [{id: x, changes: ['**'], action: ignore}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: affected, rules: [{id: x, changes: ['**'], action: require}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: affected, rules: [{id: x, changes: ['**'], action: full, tests: {classes: [example.A]}}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: affected, rules: [{id: x, changes: ['../**'], action: ignore}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: affected, rules: [{id: x, changes: ['a/**b'], action: ignore}]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, mandatory: null}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full, rules: [null]}}",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: full}}\n---\nschemaVersion: 1",
            "!!java.net.URL [secret-sentinel]",
            "schemaVersion: 1\nprofiles: {daily-testdata: {mode: smoke, mandatory: {classes: &shared [example.A], patterns: *shared}}}"
    })
    void rejectsInvalidOrAmbiguousWireInputsWithoutReturningPayloads(String yaml) {
        var failure = assertThrows(IllegalArgumentException.class, () -> SelectionPolicyYaml.parse(yaml));
        assertFalse(failure.getMessage().contains("secret-sentinel"));
        assertNull(failure.getCause());
    }

    @Test
    void canonicalEncodingNormalizesOrderAndCommentsButRetainsSemanticChanges() {
        var ruleA = new Rule("a", Set.of("src/a/**"), Action.OPTIONAL, new Targets(Set.of("example.A"), Set.of()), 1);
        var ruleB = new Rule("b", Set.of("src/b/**"), Action.IGNORE, Targets.none(), 0);
        var first = new SelectionPolicy(1, Map.of("daily-testdata", Profile.affected(Targets.none(), List.of(ruleB, ruleA), Budget.unlimited())));
        var second = new SelectionPolicy(1, Map.of("daily-testdata", Profile.affected(Targets.none(), List.of(ruleA, ruleB), Budget.unlimited())));
        assertEquals(SelectionPolicyYaml.format(first), SelectionPolicyYaml.format(second));
        assertEquals(first, SelectionPolicyYaml.parse("# editorial comment\n" + SelectionPolicyYaml.format(second)));
        assertThrows(IllegalArgumentException.class, () -> Profile.affected(Targets.none(), List.of(ruleA, ruleA), Budget.unlimited()));
    }

    @Test
    void snapshotsAllCallerCollectionsAndRejectsInvalidTypedContracts() {
        var names = new HashSet<>(Set.of("example.A"));
        var targets = new Targets(names, Set.of());
        var rules = new ArrayList<>(List.of(new Rule("rule", Set.of("**"), Action.REQUIRE, targets, 0)));
        var profiles = new HashMap<>(Map.of("daily-testdata", Profile.affected(targets, rules, Budget.unlimited())));
        var policy = new SelectionPolicy(1, profiles);
        names.clear(); rules.clear(); profiles.clear();
        assertEquals(Set.of("example.A"), policy.profiles().get("daily-testdata").mandatory().classes());
        assertEquals(1, policy.profiles().get("daily-testdata").rules().size());
        assertThrows(UnsupportedOperationException.class, () -> policy.profiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> targets.classes().clear());
        assertThrows(IllegalArgumentException.class, () -> new Budget(0, Double.NaN, 60, Overflow.FAIL));
        assertThrows(IllegalArgumentException.class, () -> new Rule("bad id", Set.of("**"), Action.IGNORE, Targets.none(), 0));
        assertThrows(IllegalArgumentException.class, () -> new SelectionPolicy(0, policy.profiles()));
    }

    @Test
    void boundsPolicySizeBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> SelectionPolicyYaml.parse("#".repeat(1_048_577)));
    }
}
