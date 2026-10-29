package io.github.sjacek.shardpilot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.TreeMap;

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
            if (fields.length != 2 || invalidClassName(fields[0])) {
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

    /**
     * Formats a validated history deterministically, independent of locale and map iteration order.
     * @param history finite nonnegative timings keyed by binary class name
     * @param precision number of fractional decimal digits, from zero to six
     * @return UTF-8-compatible TSV text with a comment header and LF line endings
     */
    public static String format(Map<String, Double> history, int precision) {
        if (precision < 0 || precision > 6) throw new IllegalArgumentException("ShardPilot precision must be between 0 and 6");
        var text = new StringBuilder("# ShardPilot duration history: binary class name<TAB>seconds\n");
        for (var entry : new TreeMap<>(history).entrySet()) {
            if (invalidClassName(entry.getKey()) || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()) || entry.getValue() < 0) {
                throw new IllegalArgumentException("ShardPilot history requires binary names and finite nonnegative timings");
            }
            text.append(entry.getKey()).append('\t').append(rounded(entry.getValue(), precision).toPlainString()).append('\n');
        }
        return text.toString();
    }

    static BigDecimal rounded(double seconds, int precision) {
        return BigDecimal.valueOf(seconds).setScale(precision, RoundingMode.HALF_UP);
    }

    /** Shared outer-class identity for planning and refresh, including deeply nested binary names. */
    static String family(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    static boolean invalidClassName(String name) {
        if (name == null || name.isEmpty()) return true;
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))) return true;
            for (int offset = Character.charCount(part.codePointAt(0)); offset < part.length();) {
                int point = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(point)) return true;
                offset += Character.charCount(point);
            }
        }
        return false;
    }

    private static IllegalArgumentException malformed(int index, String reason) {
        // Avoid echoing an arbitrary file line into a shared CI log.
        return new IllegalArgumentException("ShardPilot timing history line " + (index + 1) + ": " + reason);
    }
}
