package io.github.sjacek.shardpilot;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.SelectionDecision.*;
import static io.github.sjacek.shardpilot.SelectionPolicy.*;

/** Pure version-1 policy evaluation. No filesystem, Git, native discovery or test process is invoked. */
@NullMarked
public final class SelectionEngine {
    private SelectionEngine() { }

    /**
     * Evaluates one immutable profile against full eligibility and normalized inputs. Invalid typed
     * parameter shapes fail at construction; policy/data incompatibility produces FAILED evidence.
     * Optional ties use priority, decimal cost and binary outer name. Input iteration order is irrelevant.
     * @param policy common versioned configuration
     * @param context frozen native eligibility, observed changes and validated timing records
     * @return immutable planning result, with no selected classes on failure
     */
    public static SelectionDecision select(SelectionPolicy policy, SelectionContext context) {
        var profile = policy.profiles().get(context.profile());
        var state = new State(context, profile, digest(SelectionPolicyYaml.format(policy)));
        if (profile == null) return state.failed(Reason.UNKNOWN_PROFILE);
        if (context.eligible().isEmpty()) return state.failed(Reason.NO_ELIGIBLE_TESTS);
        if (profile.mode() != Mode.EXPLICIT && !context.explicitClasses().isEmpty()) {
            return state.failed(Reason.INVALID_EXPLICIT_REQUEST);
        }
        try {
            if (!state.costs()) return state.failed(Reason.DURATION_OVERFLOW);
            var mandatory = state.resolve(profile.mandatory());
            // Validate every configured target, even in a rule that does not match this particular diff.
            for (var rule : profile.rules()) state.resolve(rule.tests());
            if (!state.invalidTargets.isEmpty()) return state.failed(Reason.INVALID_TARGET);
            mandatory.forEach(name -> state.node(name).require(Reason.MANDATORY, ""));
            if (profile.mode() == Mode.FULL) return state.full(Reason.FULL_PROFILE, Kind.FULL);
            if (profile.mode() == Mode.EXPLICIT) {
                if (context.explicitClasses().isEmpty() || !context.eligible().containsAll(context.explicitClasses())) {
                    state.invalidTargets.addAll(context.explicitClasses());
                    state.invalidTargets.removeAll(context.eligible());
                    return state.failed(Reason.INVALID_EXPLICIT_REQUEST);
                }
                context.explicitClasses().forEach(name -> state.node(DurationHistory.family(name)).require(Reason.EXPLICIT_REQUEST, ""));
            }
            if (profile.mode() == Mode.AFFECTED) {
                if (context.changes().availability() == SelectionContext.Availability.UNKNOWN) {
                    return state.fallback(profile.onUnknownChanges(), Reason.UNKNOWN_CHANGES);
                }
                boolean full = false;
                for (String path : context.changes().paths()) {
                    boolean matched = false;
                    // Do not stop at the first matching rule: IGNORE classifies paths, never vetoes coverage.
                    for (var rule : profile.rules()) {
                        if (rule.changes().stream().noneMatch(pattern -> SelectionGlob.matches(pattern, path, "/", state.work))) continue;
                        matched = true;
                        boolean firstMatch = state.matchedRules.add(rule.id());
                        if (firstMatch && rule.action() == Action.FULL) state.nodes.values().forEach(node -> node.rules.add(rule.id()));
                        full |= rule.action() == Action.FULL;
                        for (String name : state.resolve(rule.tests())) {
                            var node = state.node(name);
                            if (rule.action() == Action.REQUIRE) node.require(Reason.REQUIRED_RULE, rule.id());
                            if (rule.action() == Action.OPTIONAL) node.optional(rule.id(), rule.priority());
                        }
                    }
                    if (!matched) state.unmatched.add(path);
                }
                if (full) return state.full(Reason.FULL_RULE, Kind.FULL_FALLBACK);
                if (!state.unmatched.isEmpty()) return state.fallback(profile.onUnmatchedChanges(), Reason.UNMATCHED_CHANGE);
            }
            return state.pack(profile);
        } catch (SelectionGlob.Limit exhausted) {
            return state.failed(Reason.RESOURCE_LIMIT);
        }
    }

    private static final class Node {
        private final String name;
        private final Set<String> members = new TreeSet<>();
        private final Set<String> rules = new TreeSet<>();
        private final Set<Reason> reasons = new TreeSet<>();
        private BigDecimal cost;
        private boolean measured;
        private boolean required;
        private boolean optional;
        private int priority = Integer.MAX_VALUE;

        private Node(String name, double unknownSeconds) {
            this.name = name;
            cost = BigDecimal.valueOf(unknownSeconds);
        }

        private void require(Reason reason, String rule) {
            required = true;
            reasons.add(reason);
            if (!rule.isEmpty()) rules.add(rule);
        }

        private void optional(String rule, int priority) {
            optional = true;
            // Multiple rules can propose one family; preserve its strongest optional priority.
            this.priority = Math.min(this.priority, priority);
            rules.add(rule);
            reasons.add(Reason.OPTIONAL_RULE);
        }
    }

    /** All mutable resolution, work accounting and evidence belong to one call, including failed calls. */
    private static final class State {
        private final SelectionContext context;
        private final @Nullable Profile profile;
        private final Budget budget;
        private final String policySha256;
        private final String identity;
        private final Map<String, Node> nodes = new TreeMap<>();
        private final Map<Targets, Set<String>> targets = new HashMap<>();
        private final Set<String> invalidTargets = new TreeSet<>();
        private final Set<String> unmatched = new TreeSet<>();
        private final Set<String> matchedRules = new TreeSet<>();
        private final Set<Reason> reasons = new TreeSet<>();
        private final SelectionGlob.Work work = new SelectionGlob.Work();

        private State(SelectionContext context, @Nullable Profile profile, String policySha256) {
            this.context = context;
            this.profile = profile;
            this.budget = profile == null ? Budget.unlimited() : profile.budget();
            this.policySha256 = policySha256;
            identity = identity(policySha256, context);
            context.eligible().forEach(name -> nodes.computeIfAbsent(DurationHistory.family(name),
                    family -> new Node(family, budget.unknownSeconds())).members.add(name));
        }

        private boolean costs() {
            var costs = new TreeMap<String, BigDecimal>();
            // Match the existing planner's additive family policy, with decimal arithmetic for limit comparisons.
            context.history().forEach((name, seconds) -> costs.merge(DurationHistory.family(name), BigDecimal.valueOf(seconds), BigDecimal::add));
            if (costs.values().stream().anyMatch(cost -> !Double.isFinite(cost.doubleValue()))) return false;
            nodes.forEach((name, node) -> {
                // A measured zero is real history. Only an absent family receives the substitute.
                node.measured = costs.containsKey(name);
                if (node.measured) node.cost = costs.getOrDefault(name, node.cost);
                else node.reasons.add(Reason.UNKNOWN_ESTIMATE);
            });
            return true;
        }

        private Set<String> resolve(Targets requested) {
            // Cache family matches, not mutable candidate flags. Required/optional provenance is accumulated later.
            return targets.computeIfAbsent(requested, target -> {
                var result = new TreeSet<String>();
                for (String name : target.classes()) {
                    if (!context.eligible().contains(name)) invalidTargets.add(name);
                    else result.add(DurationHistory.family(name));
                }
                for (String pattern : target.patterns()) {
                    boolean matched = false;
                    for (String name : context.eligible()) {
                        if (SelectionGlob.matches(pattern, name, "\\.", work)) {
                            result.add(DurationHistory.family(name));
                            matched = true;
                        }
                    }
                    if (!matched) invalidTargets.add(pattern);
                }
                return result;
            });
        }

        private SelectionDecision full(Reason reason, Kind kind) {
            reasons.add(reason);
            // Full coverage bypasses packing limits, but result() still rejects nonfinite accepted totals.
            nodes.values().forEach(node -> node.require(reason, ""));
            return result(Status.PASSED, kind, nodes.keySet());
        }

        private SelectionDecision fallback(Fallback fallback, Reason reason) {
            reasons.add(reason);
            return fallback == Fallback.FULL ? full(reason, Kind.FULL_FALLBACK) : failed(reason);
        }

        private SelectionDecision failed(Reason reason) {
            reasons.add(reason);
            return result(Status.FAILED, Kind.SUBSET, Set.of());
        }

        private SelectionDecision pack(Profile active) {
            var chosen = new TreeSet<String>();
            // Reserve required coverage before examining optional probes; rule order cannot displace it.
            nodes.values().stream().filter(node -> node.required).forEach(node -> chosen.add(node.name));
            BigDecimal seconds = cost(chosen);
            boolean overflow = exceeded(chosen.size(), seconds);
            if (overflow) {
                reasons.add(Reason.PROTECTED_BUDGET_EXCEEDED);
                if (budget.overflow() == Overflow.FAIL) return failed(Reason.PROTECTED_BUDGET_EXCEEDED);
            }
            for (var node : nodes.values().stream().filter(value -> value.optional && !value.required)
                    .sorted(Comparator.comparingInt((Node value) -> value.priority)
                            .thenComparing(value -> value.cost).thenComparing(value -> value.name)).toList()) {
                BigDecimal total = seconds.add(node.cost);
                if (budget.maxFamilies() > 0 && chosen.size() + 1 > budget.maxFamilies()) node.reasons.add(Reason.FAMILY_BUDGET);
                if (budget.maxSeconds() > 0 && total.compareTo(BigDecimal.valueOf(budget.maxSeconds())) > 0) node.reasons.add(Reason.TIME_BUDGET);
                if (!exceeded(chosen.size() + 1, total)) {
                    chosen.add(node.name);
                    seconds = total;
                }
                // A rejected candidate consumes no budget; a later, cheaper family may still fit.
            }
            if (chosen.isEmpty()) {
                // Only an automatic empty result may use fallback. An empty explicit request failed earlier.
                return fallback(active.onEmptySelection(), Reason.EMPTY_SELECTION);
            }
            return result(Status.PASSED, Kind.SUBSET, chosen);
        }

        private BigDecimal cost(Set<String> families) {
            return families.stream().sorted().map(name -> node(name).cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private Node node(String family) {
            var node = nodes.get(family);
            if (node == null) throw new IllegalStateException("Selection family is outside eligibility");
            return node;
        }

        private boolean exceeded(int families, BigDecimal cost) {
            return budget.maxFamilies() > 0 && families > budget.maxFamilies()
                    || budget.maxSeconds() > 0 && cost.compareTo(BigDecimal.valueOf(budget.maxSeconds())) > 0;
        }

        private SelectionDecision result(Status status, Kind kind, Set<String> selected) {
            if (!Double.isFinite(cost(selected).doubleValue())) {
                status = Status.FAILED;
                reasons.add(Reason.DURATION_OVERFLOW);
            }
            var evidence = new TreeMap<String, Family>();
            var protectedSet = new TreeSet<String>();
            for (var node : nodes.values()) {
                if (node.required) protectedSet.add(node.name);
                // Failure dominates all provisional choices. No partial accepted manifest may escape.
                var disposition = status == Status.FAILED && (node.required || node.optional) ? Disposition.BLOCKED
                        : status == Status.PASSED && selected.contains(node.name) ? Disposition.SELECTED
                        : node.optional ? Disposition.DEFERRED : Disposition.NOT_SELECTED;
                evidence.put(node.name, new Family(node.members, disposition, node.required, node.cost.doubleValue(),
                        node.measured, node.rules, node.reasons));
            }
            return new SelectionDecision(context, profile == null ? null : profile.mode(), status, kind, policySha256,
                    identity, evidence, reasons, unmatched, invalidTargets, matchedRules, budget,
                    exceeded(protectedSet.size(), cost(protectedSet)) || exceeded(selected.size(), cost(selected)),
                    status == Status.PASSED ? cost(selected).doubleValue() : null);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    /** Length framing prevents delimiters, Unicode or path names from creating ambiguous identities. */
    private static String identity(String policySha256, SelectionContext context) {
        try {
            // Stream framed inputs directly into the digest; large valid inventories need no duplicate buffer.
            var digest = MessageDigest.getInstance("SHA-256");
            var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            frame(output, "shardpilot-selection-engine-v1");
            frame(output, policySha256);
            frame(output, context.profile());
            names(output, context.eligible());
            frame(output, context.changes().availability().name());
            frame(output, context.changes().baseRevision());
            frame(output, context.changes().headRevision());
            names(output, context.changes().paths());
            names(output, context.explicitClasses());
            // Bind complete history, including unrelated families, so a reused decision cannot hide input drift.
            output.writeInt(context.history().size());
            for (var entry : context.history().entrySet()) {
                frame(output, entry.getKey());
                frame(output, Double.toHexString(entry.getValue()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode in-memory selection identity", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    private static void names(DataOutputStream output, Set<String> names) throws IOException {
        output.writeInt(names.size());
        for (String name : names.stream().sorted().toList()) frame(output, name);
    }

    private static void frame(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
