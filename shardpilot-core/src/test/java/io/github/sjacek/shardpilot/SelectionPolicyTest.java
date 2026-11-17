package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Java callers receive the same bounded, action-specific contract as YAML consumers. */
class SelectionPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " with-space", "x/y", ".hidden", "bad:profile", "ą-profile", "x\nprofile"})
    void rejectsIdentifiersUnsuitableForStableProfileAndRuleReferences(String id) {
        assertThrows(IllegalArgumentException.class, () -> new SelectionPolicy(1, Map.of(id, Profile.full())));
        assertThrows(IllegalArgumentException.class, () -> new Rule(id, Set.of("docs/**"), Action.IGNORE, Targets.none(), 0));
    }

    @Test
    void acceptsTheIdentifierLengthBoundaryWithoutTruncatingLongerIds() {
        String accepted = "a".repeat(71) + "-testdata";
        assertEquals(80, accepted.length());
        assertDoesNotThrow(() -> new SelectionPolicy(1, Map.of(accepted, Profile.full())));
        assertThrows(IllegalArgumentException.class, () -> new SelectionPolicy(1, Map.of("b" + accepted, Profile.full())));
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void requiresTargetsOnlyForActionsThatSelectFamilies(Action action) {
        var exact = new Targets(Set.of("example.CoreIT"), Set.of());
        boolean selects = action == Action.REQUIRE || action == Action.OPTIONAL;
        assertDoesNotThrow(() -> new Rule("rule", Set.of("src/**"), action, selects ? exact : Targets.none(), 0));
        assertThrows(IllegalArgumentException.class, () -> new Rule("rule", Set.of("src/**"), action, selects ? Targets.none() : exact, 0));
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void priorityCannotAccidentallyGiveRequireFullOrIgnoreOrderingSemantics(Action action) {
        var targets = action == Action.REQUIRE || action == Action.OPTIONAL
                ? new Targets(Set.of("example.CoreIT"), Set.of()) : Targets.none();
        if (action == Action.OPTIONAL) {
            assertDoesNotThrow(() -> new Rule("rule", Set.of("src/**"), action, targets, Integer.MAX_VALUE));
        } else {
            assertThrows(IllegalArgumentException.class, () -> new Rule("rule", Set.of("src/**"), action, targets, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new Rule("rule", Set.of("src/**"), action, targets, -1));
    }

    @Test
    void profileLimitAcceptsTheLastProfileAndRejectsTheNextOne() {
        var profiles = new HashMap<String, Profile>();
        for (int index = 0; index < 64; index++) profiles.put("profile-" + index + "-testdata", Profile.full());
        var accepted = new SelectionPolicy(1, profiles);
        profiles.put("extra-testdata", Profile.full());
        assertEquals(64, accepted.profiles().size());
        assertThrows(IllegalArgumentException.class, () -> new SelectionPolicy(1, profiles));
    }

    @Test
    void targetLimitsRejectOversizeInputsInsteadOfDroppingCoverage() {
        var classes = new HashSet<String>();
        var patterns = new HashSet<String>();
        for (int index = 0; index < 4_096; index++) classes.add("example.Case" + index);
        for (int index = 0; index < 256; index++) patterns.add("example.area" + index + ".*IT");
        var accepted = new Targets(classes, patterns);
        classes.add("example.ExtraIT");
        assertThrows(IllegalArgumentException.class, () -> new Targets(classes, patterns));
        patterns.add("example.extra.*IT");
        assertThrows(IllegalArgumentException.class, () -> new Targets(accepted.classes(), patterns));
        assertEquals(4_096, accepted.classes().size());
        assertEquals(256, accepted.patterns().size());
    }

    @Test
    void pathAndRuleLimitsRejectTheAdditionalItemWithoutChangingEarlierSnapshots() {
        var paths = new HashSet<String>();
        var rules = new ArrayList<Rule>();
        for (int index = 0; index < 256; index++) {
            paths.add("area" + index + "/**");
            rules.add(new Rule("rule-" + index, Set.of("area" + index + "/**"), Action.IGNORE, Targets.none(), 0));
        }
        var acceptedRule = new Rule("many-paths", paths, Action.IGNORE, Targets.none(), 0);
        var acceptedProfile = Profile.affected(Targets.none(), rules, Budget.unlimited());
        paths.add("extra/**");
        rules.add(new Rule("extra", Set.of("extra/**"), Action.IGNORE, Targets.none(), 0));
        assertThrows(IllegalArgumentException.class, () -> new Rule("many-paths", paths, Action.IGNORE, Targets.none(), 0));
        assertThrows(IllegalArgumentException.class, () -> Profile.affected(Targets.none(), rules, Budget.unlimited()));
        assertEquals(256, acceptedRule.changes().size());
        assertEquals(256, acceptedProfile.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> acceptedProfile.rules().clear());
    }
}
