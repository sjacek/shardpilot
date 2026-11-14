package io.github.sjacek.shardpilot;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.NullMarked;

/**
 * Frozen selection inputs, independently supplied by an adapter. No Git or runner is invoked here.
 * @param profile configured profile ID
 * @param eligible complete native candidate names, independent of any selected subset
 * @param changes normalized paths from both sides of renames/deletions, or explicit unavailable state
 * @param history complete finite nonnegative binary-class timing records
 * @param explicitClasses requested names for EXPLICIT mode only
 */
@NullMarked
public record SelectionContext(String profile, Set<String> eligible, ChangeSet changes,
                               Map<String, Double> history, Set<String> explicitClasses) {
    /** Validates all names/timings, including unrelated history, and snapshots caller collections. */
    public SelectionContext {
        SelectionPolicy.identifier(profile);
        eligible = SelectionPolicy.sorted(eligible);
        explicitClasses = SelectionPolicy.sorted(explicitClasses);
        history = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(history)));
        if (eligible.size() > 100_000 || explicitClasses.size() > 100_000 || history.size() > 100_000
                || eligible.stream().anyMatch(SelectionContext::invalidName)
                || explicitClasses.stream().anyMatch(SelectionContext::invalidName)
                || history.entrySet().stream().anyMatch(entry -> invalidName(entry.getKey())
                    || !Double.isFinite(entry.getValue()) || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Invalid selection eligibility or history");
        }
    }

    /** Distinguishes an observed empty change set from unavailable acquisition. */
    public enum Availability {
        /** Acquisition completed; an empty path set means an observed empty diff. */
        KNOWN,
        /** Acquisition could not supply a trustworthy diff. */
        UNKNOWN
    }

    /**
     * Commit IDs are optional for explicitly supplied path sets; if known, retain full IDs, not branch tips.
     * @param availability acquisition outcome
     * @param paths sorted repository-relative paths; empty when acquisition is unavailable
     * @param baseRevision full commit ID or empty for non-Git input/unavailable base
     * @param headRevision full commit ID or empty for non-Git input/unavailable head
     */
    public record ChangeSet(Availability availability, Set<String> paths, String baseRevision, String headRevision) {
        /** Enforces normalized paths, bounded counts and full revision provenance without invoking Git. */
        public ChangeSet {
            paths = SelectionPolicy.sorted(paths);
            if (paths.size() > 20_000 || availability == Availability.UNKNOWN && !paths.isEmpty()
                    || invalidRevision(baseRevision) || invalidRevision(headRevision)
                    || availability == Availability.KNOWN && baseRevision.isEmpty() != headRevision.isEmpty()) {
                throw new IllegalArgumentException("Invalid selection change provenance");
            }
            paths.forEach(path -> SelectionGlob.path(path, false));
        }

        /**
         * Creates a known non-Git change snapshot.
         * @param paths normalized externally supplied paths, without Git provenance
         * @return observed change set, including a legitimate empty diff
         */
        public static ChangeSet known(Set<String> paths) {
            return new ChangeSet(Availability.KNOWN, paths, "", "");
        }

        /**
         * Returns unavailable acquisition without invented paths or revisions.
         * @return unavailable acquisition without invented paths or revisions
         */
        public static ChangeSet unknown() {
            return new ChangeSet(Availability.UNKNOWN, Set.of(), "", "");
        }

        private static boolean invalidRevision(String value) {
            // Shape validation only. Adapters must resolve objects and verify the actual repository/diff relationship.
            return !value.isEmpty() && !value.matches("[a-f0-9]{40}|[a-f0-9]{64}");
        }
    }

    private static boolean invalidName(String name) {
        return name.length() > 512 || DurationHistory.invalidClassName(name);
    }
}
