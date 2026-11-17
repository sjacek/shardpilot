package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Versioned Git metadata extension. Contract values and raw blob text are never serialized. */
@NullMarked
final class ImpactSnapshots {
    private ImpactSnapshots() { }

    /** Stable ordering binds semantic metadata and raw hashes into a replayable decision identity. */
    static String json(Map<String, SelectionImpact.Change> changes) {
        var root = new TreeMap<String, Object>();
        changes.forEach((path, value) -> {
            var fields = new TreeMap<String, Object>();
            fields.put("format", value.format().name()); fields.put("failure", value.failure().name());
            fields.put("pointers", new ArrayList<>(value.pointers())); fields.put("beforeSha256", value.beforeSha256()); fields.put("afterSha256", value.afterSha256());
            root.put(path, fields);
        });
        return EvidenceJson.write(root);
    }

    /**
     * Transport validation is stricter than fallback policy: a malformed downloaded observation is
     * rejected, not converted to UNKNOWN/FULL. ChangeSet additionally binds each key to a changed path.
     */
    static Map<String, SelectionImpact.Change> read(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> root) || root.size() > 32) throw invalid();
        var result = new TreeMap<String, SelectionImpact.Change>();
        root.forEach((key, entry) -> {
            if (!(key instanceof String path) || !(entry instanceof Map<?, ?> map)
                    || !map.keySet().equals(Set.of("format", "failure", "pointers", "beforeSha256", "afterSha256"))
                    || !(map.get("pointers") instanceof List<?> pointers)) throw invalid();
            SelectionGlob.path(path, false);
            var changed = new TreeSet<String>();
            for (Object pointer : pointers) if (!changed.add(string(pointer))) throw invalid();
            result.put(path, new SelectionImpact.Change(SelectionImpact.Format.valueOf(string(map.get("format"))),
                    SelectionImpact.Failure.valueOf(string(map.get("failure"))), changed, string(map.get("beforeSha256")), string(map.get("afterSha256"))));
        });
        return result;
    }

    private static String string(@Nullable Object value) { if (value instanceof String text) return text; throw invalid(); }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid structured change snapshot"); }
}
