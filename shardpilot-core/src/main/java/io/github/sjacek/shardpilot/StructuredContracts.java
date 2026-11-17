package io.github.sjacek.shardpilot;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionEndEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;

/** Bounded semantic JSON/YAML diff. Only pointers and raw-byte hashes leave this comparison. */
@NullMarked
public final class StructuredContracts {
    private StructuredContracts() { }

    /**
     * Compares two exact Git blobs. A null side means an absent file, distinct from a JSON/YAML null.
     * Arrays are atomic: any element/order change affects the entire array subtree.
     * @param format declared document format
     * @param before previous blob, or null when added
     * @param after current blob, or null when deleted
     * @return complete changed pointers, or explicit unknown impact without parser payloads
     */
    public static SelectionImpact.Change compare(SelectionImpact.Format format, byte @Nullable [] before, byte @Nullable [] after) {
        if (before == null && after == null) throw invalid();
        if (before != null && before.length > 1_048_576 || after != null && after.length > 1_048_576) {
            return unknown(format, SelectionImpact.Failure.RESOURCE_LIMIT);
        }
        String a = before == null ? "" : EvidenceFiles.digest(before), b = after == null ? "" : EvidenceFiles.digest(after);
        try {
            var budget = new Budget();
            Object oldValue = before == null ? null : parse(format, before, budget);
            Object newValue = after == null ? null : parse(format, after, budget);
            var pointers = new TreeSet<String>();
            if (before == null || after == null) pointers.add("");
            else diff(oldValue, newValue, "", pointers, budget);
            return new SelectionImpact.Change(format, SelectionImpact.Failure.NONE, pointers, a, b);
        } catch (Limit exhausted) {
            // Never publish a prefix of the pointer set: that would make a partial scan look complete.
            return new SelectionImpact.Change(format, SelectionImpact.Failure.RESOURCE_LIMIT, Set.of(), a, b);
        } catch (IllegalArgumentException | ArithmeticException | java.nio.charset.CharacterCodingException | YamlEngineException failure) {
            return new SelectionImpact.Change(format, SelectionImpact.Failure.INVALID_DOCUMENT, Set.of(), a, b);
        }
    }

    static SelectionImpact.Change unknown(SelectionImpact.Format format, SelectionImpact.Failure failure) {
        return new SelectionImpact.Change(format, failure, Set.of(), "", "");
    }

    private static @Nullable Object parse(SelectionImpact.Format format, byte[] bytes, Budget budget) throws java.nio.charset.CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (format == SelectionImpact.Format.JSON) return normalize(EvidenceJson.parse(text, 100_000), budget, 0);
        var settings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(1_048_576).build();
        int depth = 0, events = 0;
        for (var event : new Parse(settings).parseString(text)) {
            if (++events > 100_000 || event instanceof CollectionStartEvent && ++depth > 32) throw new Limit();
            if (event instanceof CollectionEndEvent) depth--;
            if (event instanceof AliasEvent) throw invalid();
        }
        // Compose scalars instead of loading Double values: rounding must never hide a numeric change.
        var root = new Compose(settings).composeString(text);
        if (root.isEmpty()) throw invalid();
        return yaml(root.get(), budget, 0);
    }

    private static @Nullable Object yaml(Node node, Budget budget, int depth) {
        budget.step(depth);
        if (node instanceof ScalarNode scalar) {
            Tag tag = scalar.getTag(); String value = scalar.getValue();
            if (tag.equals(Tag.STR)) return value;
            if (tag.equals(Tag.NULL)) return null;
            if (tag.equals(Tag.BOOL)) return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "true" -> true; case "false" -> false; default -> throw invalid();
            };
            if (tag.equals(Tag.INT) || tag.equals(Tag.FLOAT)) {
                if (value.length() > 128 || !value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw invalid();
                return decimal(new BigDecimal(value));
            }
            throw invalid();
        }
        if (node instanceof MappingNode map && node.getTag().equals(Tag.MAP)) {
            var result = new TreeMap<String, @Nullable Object>();
            for (var entry : map.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key) || !key.getTag().equals(Tag.STR) || result.containsKey(key.getValue())) throw invalid();
                result.put(key.getValue(), yaml(entry.getValueNode(), budget, depth + 1));
            }
            return result;
        }
        if (node instanceof SequenceNode list && node.getTag().equals(Tag.SEQ)) {
            var result = new ArrayList<@Nullable Object>();
            for (Node item : list.getValue()) result.add(yaml(item, budget, depth + 1));
            return result;
        }
        throw invalid();
    }

    /** Both formats converge on immutable scalar semantics, notably exact decimal equality. */
    private static @Nullable Object normalize(@Nullable Object value, Budget budget, int depth) {
        budget.step(depth);
        if (value instanceof BigDecimal number) return decimal(number);
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, @Nullable Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid();
                result.put(key, normalize(entry.getValue(), budget, depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<@Nullable Object>();
            for (Object item : list) result.add(normalize(item, budget, depth + 1));
            return result;
        }
        return value;
    }

    private static BigDecimal decimal(BigDecimal value) {
        if (Math.abs((long) value.scale()) > 10000) throw invalid();
        return value.stripTrailingZeros();
    }

    /**
     * Descend only through maps present on both sides. Added/removed parents and whole arrays retain
     * their parent pointer, forcing rules to account for every potentially affected child or fall back.
     */
    private static void diff(@Nullable Object before, @Nullable Object after, String path, Set<String> changed, Budget budget) {
        budget.step(0);
        if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b) {
            var keys = new TreeSet<String>(); a.keySet().forEach(key -> keys.add((String) key)); b.keySet().forEach(key -> keys.add((String) key));
            for (String key : keys) {
                String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                if (a.containsKey(key) != b.containsKey(key)) add(changed, child);
                else diff(a.get(key), b.get(key), child, changed, budget);
            }
        } else if (!java.util.Objects.equals(before, after)) add(changed, path);
    }

    private static void add(Set<String> changes, String path) {
        pointer(path); changes.add(path); if (changes.size() > 4096) throw new Limit();
    }

    /** Validates RFC 6901 encoding; '*' has no wildcard meaning here. */
    static void pointer(String value) {
        if (value.length() > 4096 || !value.isEmpty() && !value.startsWith("/")
                || value.codePoints().anyMatch(point -> Character.isISOControl(point) || point >= 0xd800 && point <= 0xdfff)) throw invalid();
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '~') {
            if (++i == value.length() || value.charAt(i) != '0' && value.charAt(i) != '1') throw invalid();
        }
    }

    /** Segment boundaries prevent /api from matching /apis; encoded '~1' remains part of one key. */
    static boolean intersects(String watched, String changed) {
        return watched.isEmpty() || changed.isEmpty() || watched.equals(changed) || watched.startsWith(changed + "/") || changed.startsWith(watched + "/");
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid structured contract data"); }

    private static final class Budget {
        private int remaining = 500_000;

        void step(int depth) { if (depth > 32 || --remaining < 0) throw new Limit(); }
    }

    private static final class Limit extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }
}
