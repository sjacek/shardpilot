package io.github.sjacek.shardpilot;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;

/**
 * Immutable, build-tool-independent configuration for the version-1 selection engine.
 * Required parameters are non-null; adapters must snapshot their own lazy properties first.
 * @param schemaVersion supported wire-contract version, currently 1
 * @param profiles named independent suite policies, copied and ordered by name
 */
@NullMarked
public record SelectionPolicy(int schemaVersion, Map<String, Profile> profiles) {
    /** Validates version/profile bounds and snapshots the supplied profile map. */
    public SelectionPolicy {
        profiles = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(profiles)));
        if (schemaVersion != 1 || profiles.isEmpty() || profiles.size() > 64) {
            throw new IllegalArgumentException("Invalid selection policy version or profile count");
        }
        profiles.keySet().forEach(SelectionPolicy::identifier);
    }

    /** FULL selects eligibility; SMOKE selects mandatory tests; AFFECTED applies rules; EXPLICIT validates supplied names. */
    public enum Mode {
        /** Complete independently discovered eligibility. */
        FULL,
        /** Only configured mandatory families. */
        SMOKE,
        /** Mandatory families plus all applicable path rules. */
        AFFECTED,
        /** Validated requested names plus mandatory families. */
        EXPLICIT
    }

    /** Safe, observable outcome when automatic selection lacks sufficient information. */
    public enum Fallback {
        /** Select all eligibility without budget truncation. */
        FULL,
        /** Reject planning and expose no executable subset. */
        FAIL
    }

    /** Rule effects are combined; IGNORE only classifies a path and never vetoes another rule. */
    public enum Action {
        /** Protect matched target families from budget deferral. */
        REQUIRE,
        /** Consider target families only when the budget permits. */
        OPTIONAL,
        /** Request complete eligibility regardless of other path classifications. */
        FULL,
        /** Classify changed paths without vetoing overlapping rules. */
        IGNORE
    }

    /** Protected tests are never dropped to meet a budget. */
    public enum Overflow {
        /** Reject a protected set exceeding either configured limit. */
        FAIL,
        /** Preserve protected coverage and report the exceeded estimate. */
        KEEP_REQUIRED
    }

    /**
     * Exact binary names and segment patterns. Every configured target must match eligibility.
     * A nested match expands to all eligible members of its outer family.
     * @param classes exact names
     * @param patterns dot-separated globs; ** is a whole-segment wildcard
     */
    public record Targets(Set<String> classes, Set<String> patterns) {
        /** Validates target syntax and bounds, then snapshots both sets. */
        public Targets {
            classes = sorted(classes);
            patterns = sorted(patterns);
            if (classes.size() > 4096 || patterns.size() > 256 || classes.stream().anyMatch(name ->
                    name.length() > 512 || DurationHistory.invalidClassName(name))) {
                throw new IllegalArgumentException("Invalid selection targets");
            }
            patterns.forEach(SelectionGlob::classPattern);
        }

        /**
         * Returns no configured exact names or patterns.
         * @return no configured exact names or patterns
         */
        public static Targets none() {
            return new Targets(Set.of(), Set.of());
        }

        /**
         * Returns whether both target sets are empty.
         * @return whether both target sets are empty
         */
        public boolean isEmpty() {
            return classes.isEmpty() && patterns.isEmpty();
        }
    }

    /**
     * A path rule; all matches contribute and FULL takes precedence over subset selection.
     * @param id stable identifier retained in reports
     * @param changes repository-relative path globs
     * @param action required/optional targets, full execution or explicit path classification
     * @param tests targets, empty only for FULL/IGNORE
     * @param priority optional selection order, smaller values first, then cost and binary family name
     */
    public record Rule(String id, Set<String> changes, Action action, Targets tests, int priority) {
        /** Validates rule shape, bounded paths and action-specific target/priority constraints. */
        public Rule {
            identifier(id);
            changes = sorted(changes);
            boolean targetsRequired = action == Action.REQUIRE || action == Action.OPTIONAL;
            if (changes.isEmpty() || changes.size() > 256 || priority < 0
                    || targetsRequired && tests.isEmpty() || !targetsRequired && !tests.isEmpty()
                    || action != Action.OPTIONAL && priority != 0) {
                throw new IllegalArgumentException("Invalid selection rule configuration");
            }
            changes.forEach(pattern -> SelectionGlob.path(pattern, true));
        }
    }

    /**
     * Family costs use history, substituting unknownSeconds once per unmeasured family.
     * Zero maxFamilies/maxSeconds means unlimited. Limits are estimates, not process timeouts.
     * @param maxFamilies maximum selected outer families or zero
     * @param maxSeconds estimated seconds limit or zero
     * @param unknownSeconds finite positive substitute
     * @param overflow protected-set overflow behavior
     */
    public record Budget(int maxFamilies, double maxSeconds, double unknownSeconds, Overflow overflow) {
        /** Rejects negative/nonfinite limits and nonpositive unknown-family estimates. */
        public Budget {
            if (maxFamilies < 0 || !Double.isFinite(maxSeconds) || maxSeconds < 0
                    || !Double.isFinite(unknownSeconds) || unknownSeconds <= 0) {
                throw new IllegalArgumentException("Invalid selection budget");
            }
        }

        /**
         * Returns no family/time limit, a 60-second unknown estimate and protected overflow rejection.
         * @return no family/time limit, a 60-second unknown estimate and protected overflow rejection
         */
        public static Budget unlimited() {
            return new Budget(0, 0, 60, Overflow.FAIL);
        }
    }

    /**
     * One named profile. Only AFFECTED accepts path rules; supplied explicit names belong to the context.
     * @param mode selection algorithm
     * @param mandatory protected targets
     * @param rules path rules, copied and sorted by stable ID
     * @param budget family/time limits and unmeasured estimates
     * @param onUnknownChanges action when change acquisition is unavailable
     * @param onUnmatchedChanges action for unclassified paths
     * @param onEmptySelection action when an automatic profile would select nothing
     */
    public record Profile(Mode mode, Targets mandatory, List<Rule> rules, Budget budget,
                          Fallback onUnknownChanges, Fallback onUnmatchedChanges, Fallback onEmptySelection) {
        /** Snapshots rules by ID and rejects duplicates or rules on a non-affected profile. */
        public Profile {
            rules = List.copyOf(rules).stream().sorted(java.util.Comparator.comparing(Rule::id)).toList();
            if (rules.size() > 256 || mode != Mode.AFFECTED && !rules.isEmpty()
                    || rules.stream().map(Rule::id).distinct().count() != rules.size()) {
                throw new IllegalArgumentException("Invalid selection profile rules");
            }
        }

        /**
         * Default automatic fallbacks are full execution, with no implicit empty success.
         * @param mandatory protected targets
         * @param rules all contributing path rules
         * @param budget optional packing limits and protected overflow policy
         * @return affected profile using full fallbacks
         */
        public static Profile affected(Targets mandatory, List<Rule> rules, Budget budget) {
            return new Profile(Mode.AFFECTED, mandatory, rules, budget, Fallback.FULL, Fallback.FULL, Fallback.FULL);
        }

        /**
         * Returns full eligibility with unlimited estimates.
         * @return full eligibility with unlimited estimates
         */
        public static Profile full() {
            return new Profile(Mode.FULL, Targets.none(), List.of(), Budget.unlimited(),
                    Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        }

        /**
         * Creates a mandatory-only profile.
         * @param mandatory smoke targets, all protected
         * @param budget limits and protected overflow policy
         * @return smoke profile rejecting an empty result
         */
        public static Profile smoke(Targets mandatory, Budget budget) {
            return new Profile(Mode.SMOKE, mandatory, List.of(), budget, Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        }

        /**
         * Creates a profile accepting a validated explicit context request.
         * @param mandatory targets added to the exact context request
         * @param budget limits and protected overflow policy
         * @return explicit profile that rejects invalid/empty requests
         */
        public static Profile explicit(Targets mandatory, Budget budget) {
            return new Profile(Mode.EXPLICIT, mandatory, List.of(), budget, Fallback.FAIL, Fallback.FAIL, Fallback.FAIL);
        }
    }

    static void identifier(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Invalid selection identifier");
        }
    }

    static Set<String> sorted(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(Set.copyOf(values)));
    }
}
