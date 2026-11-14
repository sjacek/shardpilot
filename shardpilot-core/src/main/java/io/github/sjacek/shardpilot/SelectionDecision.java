package io.github.sjacek.shardpilot;

import io.github.sjacek.shardpilot.SelectionPolicy.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Immutable selection evidence. A passed decision proves planning only, never native execution. */
@NullMarked
public final class SelectionDecision {
    /** A failed plan has no executable selected manifest. */
    public enum Status {
        /** A nonempty executable selection was accepted. */
        PASSED,
        /** Planning rejected the inputs; selected classes are empty. */
        FAILED
    }

    /** Full fallback stays distinguishable from an explicitly requested full profile. */
    public enum Kind {
        /** Complete eligibility requested by a full profile. */
        FULL,
        /** A subset decision or a rejected planning attempt. */
        SUBSET,
        /** Complete eligibility requested by a rule or safe fallback. */
        FULL_FALLBACK
    }

    /** BLOCKED describes intended candidates in a failed decision, not accepted selections. */
    public enum Disposition {
        /** Included in the accepted executable subset. */
        SELECTED,
        /** Optional target did not fit the declared budget. */
        DEFERRED,
        /** No evaluated target added this family; failure may stop classification early. */
        NOT_SELECTED,
        /** Candidate could not be accepted because planning failed. */
        BLOCKED
    }

    /** Stable metadata-only reasons, shared by Java, JSON and HTML. */
    public enum Reason {
        /** Profile requested complete eligibility. */
        FULL_PROFILE,
        /** A matching rule requested complete eligibility. */
        FULL_RULE,
        /** Explicit context request protected this family. */
        EXPLICIT_REQUEST,
        /** Profile mandatory targets protected this family. */
        MANDATORY,
        /** Matching require rule protected this family. */
        REQUIRED_RULE,
        /** Matching optional rule proposed this family. */
        OPTIONAL_RULE,
        /** History lacked this family; the configured substitute was used. */
        UNKNOWN_ESTIMATE,
        /** Optional addition exceeded the estimated seconds limit. */
        TIME_BUDGET,
        /** Optional addition exceeded the outer-family limit. */
        FAMILY_BUDGET,
        /** Requested profile ID was absent from configuration. */
        UNKNOWN_PROFILE,
        /** Full eligibility contained no classes. */
        NO_ELIGIBLE_TESTS,
        /** A configured exact target or pattern did not match eligibility. */
        INVALID_TARGET,
        /** Explicit names were empty, foreign or supplied to a different mode. */
        INVALID_EXPLICIT_REQUEST,
        /** Trustworthy change acquisition was unavailable. */
        UNKNOWN_CHANGES,
        /** A changed path had no applicable rule. */
        UNMATCHED_CHANGE,
        /** Automatic selection produced no accepted family. */
        EMPTY_SELECTION,
        /** All protected families together exceeded a declared limit. */
        PROTECTED_BUDGET_EXCEEDED,
        /** Bounded pattern evaluation exhausted its work allowance. */
        RESOURCE_LIMIT,
        /** Family or accepted total cost could not be reported as a finite double. */
        DURATION_OVERFLOW
    }

    /**
     * One outer family and its actual eligible members. Family names never invent absent bytecode.
     * @param members eligible binary names
     * @param disposition accepted, deferred, not requested by completed evaluation or blocked by failure
     * @param protectedTarget required by profile/rule/request; never discarded for a budget
     * @param estimatedSeconds measured family sum or configured substitute
     * @param measured whether history contained this family
     * @param rules matching rule IDs
     * @param reasons stable explanations
     */
    public record Family(Set<String> members, Disposition disposition, boolean protectedTarget,
                         double estimatedSeconds, boolean measured, Set<String> rules, Set<Reason> reasons) {
        /** Snapshots evidence collections and enforces nonempty members and finite nonnegative cost. */
        public Family {
            members = SelectionPolicy.sorted(members);
            rules = SelectionPolicy.sorted(rules);
            reasons = Collections.unmodifiableSet(new TreeSet<>(Set.copyOf(reasons)));
            if (members.isEmpty() || !Double.isFinite(estimatedSeconds) || estimatedSeconds < 0) {
                throw new IllegalArgumentException("Invalid selection family evidence");
            }
        }
    }

    private final SelectionContext context;

    private final @Nullable Mode mode;

    private final Status status;

    private final Kind kind;

    private final String policySha256;

    private final String identity;

    private final Map<String, Family> families;

    private final Set<String> selected;

    private final Set<Reason> reasons;

    private final Set<String> unmatchedPaths;

    private final Set<String> invalidTargets;

    private final Set<String> matchedRules;

    private final SelectionPolicy.Budget budget;

    private final boolean budgetExceeded;

    private final @Nullable Double estimatedSeconds;

    SelectionDecision(SelectionContext context, @Nullable Mode mode, Status status, Kind kind,
                      String policySha256, String identity, Map<String, Family> families, Set<Reason> reasons,
                      Set<String> unmatchedPaths, Set<String> invalidTargets, Set<String> matchedRules,
                      SelectionPolicy.Budget budget, boolean budgetExceeded, @Nullable Double estimatedSeconds) {
        this.context = context;
        this.mode = mode;
        this.status = status;
        this.kind = kind;
        this.policySha256 = policySha256;
        this.identity = identity;
        this.families = Collections.unmodifiableMap(new TreeMap<>(families));
        var selected = new TreeSet<String>();
        families.values().stream().filter(family -> family.disposition() == Disposition.SELECTED)
                .forEach(family -> selected.addAll(family.members()));
        if (!context.eligible().containsAll(selected) || status == Status.FAILED && !selected.isEmpty()
                || status == Status.FAILED && estimatedSeconds != null
                || status == Status.PASSED && (estimatedSeconds == null || !Double.isFinite(estimatedSeconds) || estimatedSeconds < 0)) {
            throw new IllegalArgumentException("Invalid accepted selection evidence");
        }
        this.selected = SelectionPolicy.sorted(selected);
        this.reasons = Collections.unmodifiableSet(new TreeSet<>(reasons));
        this.unmatchedPaths = SelectionPolicy.sorted(unmatchedPaths);
        this.invalidTargets = SelectionPolicy.sorted(invalidTargets);
        this.matchedRules = SelectionPolicy.sorted(matchedRules);
        this.budget = budget;
        this.budgetExceeded = budgetExceeded;
        this.estimatedSeconds = estimatedSeconds;
    }

    /**
     * Returns planning acceptance, independent of native execution.
     * @return planning acceptance, independent of native execution
     */
    public Status status() { return status; }

    /**
     * Returns full, subset or observable full fallback.
     * @return full, subset or observable full fallback
     */
    public Kind kind() { return kind; }

    /**
     * Returns requested profile ID, including an unknown ID on failure.
     * @return requested profile ID, including an unknown ID on failure
     */
    public String profile() { return context.profile(); }

    /**
     * Returns resolved profile algorithm, or null for an unknown profile.
     * @return resolved profile algorithm, or null for an unknown profile
     */
    public @Nullable Mode mode() { return mode; }

    /**
     * Returns canonical semantic policy SHA-256.
     * @return canonical semantic policy SHA-256
     */
    public String policySha256() { return policySha256; }

    /**
     * Returns SHA-256 binding the versioned policy and every normalized planning input.
     * @return SHA-256 binding the versioned policy and every normalized planning input
     */
    public String identity() { return identity; }

    /**
     * Returns complete immutable eligibility E, independent of selection.
     * @return complete immutable eligibility E, independent of selection
     */
    public Set<String> eligible() { return context.eligible(); }

    /**
     * Returns immutable accepted S, always empty on failure.
     * @return immutable accepted S, always empty on failure
     */
    public Set<String> selected() { return selected; }

    /**
     * Returns outer-family evidence ordered by binary family name.
     * @return outer-family evidence ordered by binary family name
     */
    public Map<String, Family> families() { return families; }

    /**
     * Returns decision-wide fallback/failure reasons; target explanations live in family evidence.
     * @return decision-wide fallback/failure reasons; target explanations live in family evidence
     */
    public Set<Reason> reasons() { return reasons; }

    /**
     * Returns observed changed paths without a matching rule.
     * @return observed changed paths without a matching rule
     */
    public Set<String> unmatchedPaths() { return unmatchedPaths; }

    /**
     * Returns configured names/patterns rejected against eligibility.
     * @return configured names/patterns rejected against eligibility
     */
    public Set<String> invalidTargets() { return invalidTargets; }

    /**
     * Returns IDs of all activated rules, including path-only ignore/full rules.
     * @return IDs of all activated rules, including path-only ignore/full rules
     */
    public Set<String> matchedRules() { return matchedRules; }

    /**
     * Returns resolved limits and unknown-family estimate.
     * @return resolved limits and unknown-family estimate
     */
    public SelectionPolicy.Budget budget() { return budget; }

    /**
     * Returns whether protected or selected coverage exceeded either configured limit.
     * @return whether protected or selected coverage exceeded either configured limit
     */
    public boolean budgetExceeded() { return budgetExceeded; }

    /**
     * Returns accepted decimal family cost, rounded once for reporting; null when no plan was accepted.
     * @return accepted decimal family cost, rounded once for reporting; null when no plan was accepted
     */
    public @Nullable Double estimatedSeconds() {
        return estimatedSeconds;
    }

    /**
     * Returns deterministic JSON with input identity, protected costs and every optional deferral.
     * @return deterministic JSON with input identity, protected costs and every optional deferral
     */
    public String json() {
        String familyJson = String.join(",", families.entrySet().stream().map(entry -> {
            var family = entry.getValue();
            return "{\"family\":" + quote(entry.getKey()) + ",\"members\":" + strings(family.members())
                    + ",\"disposition\":" + quote(family.disposition().name()) + ",\"protected\":" + family.protectedTarget()
                    + ",\"estimatedSeconds\":" + family.estimatedSeconds() + ",\"measured\":" + family.measured()
                    + ",\"rules\":" + strings(family.rules()) + ",\"reasons\":" + enums(family.reasons()) + "}";
        }).toList());
        return "{\"schemaVersion\":1,\"engineVersion\":1,\"status\":" + quote(status.name())
                + ",\"kind\":" + quote(kind.name()) + ",\"profile\":" + quote(profile())
                + ",\"mode\":" + (mode == null ? "null" : quote(mode.name()))
                + ",\"policySha256\":" + quote(policySha256) + ",\"identity\":" + quote(identity)
                + ",\"changes\":{\"availability\":" + quote(context.changes().availability().name())
                + ",\"baseRevision\":" + quote(context.changes().baseRevision()) + ",\"headRevision\":" + quote(context.changes().headRevision())
                + ",\"pathCount\":" + context.changes().paths().size() + "},\"eligibleClasses\":" + eligible().size()
                + ",\"selectedClasses\":" + selected.size() + ",\"selectedFamilies\":"
                + families.values().stream().filter(family -> family.disposition() == Disposition.SELECTED).count()
                + ",\"estimatedSeconds\":" + estimatedSeconds() + ",\"budget\":{\"maxFamilies\":" + budget.maxFamilies()
                + ",\"maxSeconds\":" + budget.maxSeconds() + ",\"unknownSeconds\":" + budget.unknownSeconds()
                + ",\"overflow\":" + quote(budget.overflow().name()) + ",\"exceeded\":" + budgetExceeded
                + "},\"reasons\":" + enums(reasons) + ",\"unmatchedPaths\":" + strings(unmatchedPaths)
                + ",\"invalidTargets\":" + strings(invalidTargets) + ",\"matchedRules\":" + strings(matchedRules)
                + ",\"families\":[" + familyJson + "]}\n";
    }

    /**
     * Returns standalone rendering of the JSON evidence with artifact-derived text escaped.
     * @return standalone rendering of the JSON evidence with artifact-derived text escaped
     */
    public String html() {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>ShardPilot policy decision</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:2rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>"
                + "</head><body><h1>Selection decision: " + status + "</h1><p>Class-family planning; native execution must be verified separately.</p><pre>"
                + json().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre></body></html>";
    }

    /**
     * Writes into a dedicated decision output directory. A failed result removes earlier executable
     * snapshots/identities; callers protect their maintained inputs and retain native failure gates.
     * @param directory generated output, separate from maintained policy/manifests/history
     * @throws IOException if local artifacts cannot be written
     */
    public void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        // Invalidate both executable sidecars first, even if a later diagnostic write fails with IOException.
        Files.deleteIfExists(directory.resolve("selected.yaml"));
        Files.deleteIfExists(directory.resolve("decision.id"));
        Files.writeString(directory.resolve("decision.json"), json(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("decision.html"), html(), StandardCharsets.UTF_8);
        ClassManifest.write(directory.resolve("eligible.yaml"), eligible());
        if (status == Status.PASSED) {
            ClassManifest.write(directory.resolve("selected.yaml"), selected);
            Files.writeString(directory.resolve("decision.id"), identity + "\n", StandardCharsets.UTF_8);
        }
    }

    private static String quote(String value) { return ShardCoverage.quote(value); }

    private static String strings(Set<String> values) {
        return "[" + String.join(",", values.stream().sorted().map(SelectionDecision::quote).toList()) + "]";
    }

    private static String enums(Set<Reason> values) {
        return "[" + String.join(",", values.stream().map(Enum::name).sorted().map(SelectionDecision::quote).toList()) + "]";
    }
}
