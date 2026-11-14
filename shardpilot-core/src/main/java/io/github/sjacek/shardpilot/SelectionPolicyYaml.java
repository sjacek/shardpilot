package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.events.CollectionEndEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/** Strict, versioned policy transport shared by both build-tool adapters; no executable expressions. */
@NullMarked
public final class SelectionPolicyYaml {
    private SelectionPolicyYaml() { }

    /**
     * Reads a bounded UTF-8 policy without trusting a separate file-size check.
     * @param file maintained policy file
     * @return parsed immutable policy
     * @throws java.io.IOException if the file is inaccessible or contains malformed UTF-8
     */
    public static SelectionPolicy read(java.nio.file.Path file) throws java.io.IOException {
        return parse(readText(file, 1_048_576));
    }

    static String readText(java.nio.file.Path file, int limit) throws java.io.IOException {
        byte[] bytes;
        try (var stream = java.nio.file.Files.newInputStream(file)) { bytes = stream.readNBytes(limit + 1); }
        if (bytes.length > limit) throw new IllegalArgumentException("Selection input exceeds size limit");
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Parses one bounded YAML document. Unknown fields, duplicate keys/list values, collection aliases and unsafe tags
     * are rejected. Diagnostics deliberately exclude parser exception payloads and input fragments.
     * @param text UTF-8 policy snapshot
     * @return validated immutable configuration
     */
    public static SelectionPolicy parse(String text) {
        if (text.length() > 1_048_576) throw new IllegalArgumentException("Selection policy exceeds size limit");
        var root = fields(load(text, 1_048_576), Set.of("schemaVersion", "profiles"));
        if (!root.containsKey("schemaVersion") || !root.containsKey("profiles")) throw invalid();
        var profiles = fields(root.get("profiles"), null);
        var parsed = new TreeMap<String, SelectionPolicy.Profile>();
        profiles.forEach((name, value) -> parsed.put(name, profile(value)));
        return new SelectionPolicy(integer(root.get("schemaVersion")), parsed);
    }

    /** Shared bounded composition for policy and frozen input metadata; errors never retain payloads. */
    static @Nullable Object load(String text, int limit) {
        if (text.length() > limit) throw invalid();
        try {
            var settings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
                    .setCodePointLimit(limit).build();
            // Bound events before recursive object composition; policy v1 needs fewer than 16 levels.
            int depth = 0;
            int events = 0;
            for (var event : new Parse(settings).parseString(text)) {
                if (++events > 100_000) throw invalid();
                if (event instanceof CollectionStartEvent && ++depth > 16) throw invalid();
                if (event instanceof CollectionEndEvent) depth--;
            }
            return new Load(settings).loadFromString(text);
        } catch (YamlEngineException invalid) {
            // Parser diagnostics may embed configuration fragments; keep both message and cause out of evidence.
            throw new IllegalArgumentException("Invalid selection policy YAML");
        }
    }

    private static SelectionPolicy.Profile profile(@Nullable Object value) {
        var fields = fields(value, Set.of("mode", "mandatory", "rules", "budget", "onUnknownChanges",
                "onUnmatchedChanges", "onEmptySelection"));
        var mode = enumeration(fields.get("mode"), SelectionPolicy.Mode.class);
        var rules = new ArrayList<SelectionPolicy.Rule>();
        if (!(fields.getOrDefault("rules", List.of()) instanceof List<?> list)) throw invalid();
        for (Object entry : list) {
            var rule = fields(entry, Set.of("id", "changes", "action", "tests", "priority"));
            rules.add(new SelectionPolicy.Rule(string(rule.get("id")), strings(rule.get("changes")),
                    enumeration(rule.get("action"), SelectionPolicy.Action.class),
                    targets(rule.getOrDefault("tests", Map.of())), integer(rule.getOrDefault("priority", 0))));
        }
        var budget = fields(fields.getOrDefault("budget", Map.of()),
                Set.of("maxFamilies", "maxSeconds", "unknownSeconds", "overflow"));
        var limits = new SelectionPolicy.Budget(integer(budget.getOrDefault("maxFamilies", 0)),
                number(budget.getOrDefault("maxSeconds", 0)), number(budget.getOrDefault("unknownSeconds", 60)),
                enumeration(budget.getOrDefault("overflow", "fail"), SelectionPolicy.Overflow.class));
        String fallback = mode == SelectionPolicy.Mode.AFFECTED ? "full" : "fail";
        // Automatic selection defaults to complete coverage; an empty smoke/explicit suite is a configuration error.
        return new SelectionPolicy.Profile(mode, targets(fields.getOrDefault("mandatory", Map.of())), rules, limits,
                enumeration(fields.getOrDefault("onUnknownChanges", fallback), SelectionPolicy.Fallback.class),
                enumeration(fields.getOrDefault("onUnmatchedChanges", fallback), SelectionPolicy.Fallback.class),
                enumeration(fields.getOrDefault("onEmptySelection", fallback), SelectionPolicy.Fallback.class));
    }

    private static SelectionPolicy.Targets targets(@Nullable Object value) {
        var fields = fields(value, Set.of("classes", "patterns"));
        return new SelectionPolicy.Targets(strings(fields.getOrDefault("classes", List.of())),
                strings(fields.getOrDefault("patterns", List.of())));
    }

    /**
     * Canonical semantic YAML: profile/rule/target order is normalized, all defaults are explicit.
     * @param policy validated immutable configuration
     * @return deterministic YAML text used to hash policy semantics
     */
    public static String format(SelectionPolicy policy) {
        var profiles = new LinkedHashMap<String, Object>();
        policy.profiles().forEach((name, profile) -> {
            var rules = new ArrayList<Map<String, Object>>();
            for (var rule : profile.rules()) {
                rules.add(mapping("id", rule.id(), "changes", new ArrayList<>(rule.changes()),
                        "action", word(rule.action()), "tests", targets(rule.tests()), "priority", rule.priority()));
            }
            var budget = profile.budget();
            profiles.put(name, mapping("mode", word(profile.mode()), "mandatory", targets(profile.mandatory()),
                    "rules", rules, "budget", mapping("maxFamilies", budget.maxFamilies(), "maxSeconds", budget.maxSeconds(),
                            "unknownSeconds", budget.unknownSeconds(), "overflow", word(budget.overflow())),
                    "onUnknownChanges", word(profile.onUnknownChanges()), "onUnmatchedChanges", word(profile.onUnmatchedChanges()),
                    "onEmptySelection", word(profile.onEmptySelection())));
        });
        return new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setSplitLines(false)
                .setIndent(2).setIndicatorIndent(2).setIndentWithIndicator(true).build())
                .dumpToString(mapping("schemaVersion", policy.schemaVersion(), "profiles", profiles));
    }

    private static Map<String, Object> targets(SelectionPolicy.Targets targets) {
        return mapping("classes", new ArrayList<>(targets.classes()), "patterns", new ArrayList<>(targets.patterns()));
    }

    private static Map<String, Object> mapping(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static String word(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <T extends Enum<T>> T enumeration(@Nullable Object value, Class<T> type) {
        String text = string(value);
        for (T candidate : type.getEnumConstants()) if (word(candidate).equals(text)) return candidate;
        throw invalid();
    }

    private static String string(@Nullable Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static int integer(@Nullable Object value) {
        // Do not coerce 1.0, quoted numerals or overflowing integers into a different wire contract.
        if (!(value instanceof Integer number)) throw invalid();
        return number;
    }

    private static double number(@Nullable Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid();
        return number.doubleValue();
    }

    private static Set<String> strings(@Nullable Object value) {
        if (!(value instanceof List<?> list)) throw invalid();
        var result = new TreeSet<String>();
        for (Object item : list) if (!result.add(string(item))) throw invalid();
        return result;
    }

    private static Map<String, Object> fields(@Nullable Object value, @Nullable Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, entry) -> {
            String name = string(key);
            if (entry == null || allowed != null && !allowed.contains(name)) throw invalid();
            result.put(name, entry);
        });
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid selection policy fields or values");
    }
}
