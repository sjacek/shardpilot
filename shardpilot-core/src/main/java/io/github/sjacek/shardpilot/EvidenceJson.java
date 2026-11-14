package io.github.sjacek.shardpilot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Small strict JSON codec for bounded C2 documents; YAML coercions and duplicate keys are forbidden. */
@NullMarked
final class EvidenceJson {
    private final String text;
    private int position;

    private EvidenceJson(String text) { this.text = text; }

    static @Nullable Object parse(String text) {
        var parser = new EvidenceJson(text);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) throw invalid();
        return value;
    }

    private @Nullable Object value(int depth) {
        if (depth > 32) throw invalid();
        whitespace();
        if (position == text.length()) throw invalid();
        char token = text.charAt(position);
        if (token == '"') return string();
        if (token == '{') {
            position++;
            var fields = new LinkedHashMap<String, @Nullable Object>();
            if (take('}')) return fields;
            do {
                whitespace();
                if (position == text.length() || text.charAt(position) != '"') throw invalid();
                String key = string();
                if (!take(':') || fields.containsKey(key)) throw invalid();
                fields.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}')) throw invalid();
            return fields;
        }
        if (token == '[') {
            position++;
            var elements = new ArrayList<@Nullable Object>();
            if (take(']')) return elements;
            do { elements.add(value(depth + 1)); } while (take(','));
            if (!take(']')) throw invalid();
            return elements;
        }
        for (String literal : List.of("true", "false", "null")) {
            if (text.startsWith(literal, position)) {
                position += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        // Preserve exact decimal syntax until the document contract checks integer ranges.
        // Converting through double would lose large process/count values before validation.
        int start = position;
        if (token == '-') position++;
        if (position == text.length()) throw invalid();
        if (text.charAt(position) == '0') position++;
        else {
            if (text.charAt(position) < '1' || text.charAt(position) > '9') throw invalid();
            while (position < text.length() && digit(text.charAt(position))) position++;
        }
        if (position < text.length() && text.charAt(position) == '.') {
            position++;
            int digits = position;
            while (position < text.length() && digit(text.charAt(position))) position++;
            if (digits == position) throw invalid();
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
            int digits = position;
            while (position < text.length() && digit(text.charAt(position))) position++;
            if (digits == position) throw invalid();
        }
        try { return new BigDecimal(text.substring(start, position)); }
        catch (NumberFormatException failure) { throw invalid(); }
    }

    private String string() {
        position++;
        var result = new StringBuilder();
        while (position < text.length()) {
            char value = text.charAt(position++);
            if (value == '"') {
                // UTF-8 decoding validates original bytes, not escaped UTF-16 code units. Validate
                // the composed string too so escapes cannot smuggle an unpaired surrogate.
                for (int index = 0; index < result.length(); index++) {
                    char character = result.charAt(index);
                    if (Character.isHighSurrogate(character)) {
                        if (++index == result.length() || !Character.isLowSurrogate(result.charAt(index))) throw invalid();
                    } else if (Character.isLowSurrogate(character)) throw invalid();
                }
                return result.toString();
            }
            if (value < 32) throw invalid();
            if (value == '\\') {
                if (position == text.length()) throw invalid();
                value = text.charAt(position++);
                value = switch (value) {
                    case '"', '\\', '/' -> value;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw invalid();
                };
            }
            result.append(value);
        }
        throw invalid();
    }

    private char unicode() {
        if (position + 4 > text.length()) throw invalid();
        int result = 0;
        for (int index = 0; index < 4; index++) {
            char character = text.charAt(position++);
            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(character));
            if (digit < 0) throw invalid();
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private boolean take(char token) {
        whitespace();
        if (position == text.length() || text.charAt(position) != token) return false;
        position++;
        return true;
    }

    private void whitespace() {
        while (position < text.length() && " \r\n\t".indexOf(text.charAt(position)) >= 0) position++;
    }

    private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid C2 JSON document"); }

    static String write(@Nullable Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            var result = new StringBuilder("\"");
            for (char character : string.toCharArray()) {
                switch (character) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> {
                        if (character < 32) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                        else result.append(character);
                    }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            var fields = new ArrayList<String>();
            map.forEach((key, item) -> fields.add(write(key) + ":" + write(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> values) {
            var items = new ArrayList<String>();
            values.forEach(item -> items.add(write(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw invalid();
    }
}
