package io.github.sjacek.shardpilot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads portable, class-level timing metadata, never test output or executable build code. */
public final class DurationHistory {
    private DurationHistory() { }

    /**
     * Parses tab-separated binary class names and nonnegative finite durations in seconds.
     * Blank lines and full-line comments are accepted; duplicate class records are rejected.
     *
     * @param text timing file contents, or an empty string for a cold start
     * @return immutable measured durations, keyed by binary class name
     * @throws IllegalArgumentException if any non-comment record is malformed
     */
    public static Map<String, Double> parse(String text) {
        var durations = new LinkedHashMap<String, Double>();
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || !validClassName(fields[0])) {
                throw malformed(index, "expected <binary.class.Name><TAB><seconds>");
            }
            double seconds;
            try {
                seconds = Double.parseDouble(fields[1]);
            } catch (NumberFormatException failure) {
                throw malformed(index, "duration must be a number");
            }
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw malformed(index, "duration must be finite and nonnegative");
            }
            if (durations.putIfAbsent(fields[0], seconds) != null) {
                throw malformed(index, "duplicate class record");
            }
        }
        return Map.copyOf(durations);
    }

    private static boolean validClassName(String name) {
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))) return false;
            for (int offset = Character.charCount(part.codePointAt(0)); offset < part.length();) {
                int point = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(point)) return false;
                offset += Character.charCount(point);
            }
        }
        return true;
    }

    private static IllegalArgumentException malformed(int index, String reason) {
        // Avoid echoing an arbitrary file line into a shared CI log.
        return new IllegalArgumentException("ShardPilot timing history line " + (index + 1) + ": " + reason);
    }
}
