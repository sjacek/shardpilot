package io.github.sjacek.shardpilot;

import java.io.Serial;
import org.jspecify.annotations.NullMarked;

/** Bounded segment globs; user patterns are never compiled as backtracking regular expressions. */
@NullMarked
final class SelectionGlob {
    private SelectionGlob() { }

    static void path(String value, boolean pattern) {
        // Reject unpaired surrogates: UTF-8 replacement would make distinct inputs share an identity.
        if (value.isEmpty() || value.length() > 4096 || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.codePoints().anyMatch(point ->
                    Character.isISOControl(point) || point >= 0xD800 && point <= 0xDFFF)) {
            throw new IllegalArgumentException("Selection paths must be bounded repository-relative POSIX paths");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || pattern && part.contains("**") && !part.equals("**")) {
                throw new IllegalArgumentException("Invalid selection path segment");
            }
        }
    }

    static void classPattern(String pattern) {
        if (pattern.isEmpty() || pattern.length() > 512) throw new IllegalArgumentException("Invalid selection class pattern");
        for (String part : pattern.split("\\.", -1)) {
            if (part.equals("**")) continue;
            if (part.isEmpty() || part.contains("**") || part.codePoints().anyMatch(point ->
                    !Character.isJavaIdentifierPart(point) && point != '*' && point != '?')
                    || !(Character.isJavaIdentifierStart(part.codePointAt(0)) || part.startsWith("*") || part.startsWith("?"))) {
                throw new IllegalArgumentException("Invalid selection class pattern segment");
            }
        }
    }

    static boolean matches(String pattern, String value, String separator, Work work) {
        String[] patterns = pattern.split(separator, -1);
        String[] values = value.split(separator, -1);
        boolean[] previous = new boolean[values.length + 1];
        previous[0] = true;
        for (String part : patterns) {
            boolean[] next = new boolean[values.length + 1];
            next[0] = part.equals("**") && previous[0];
            for (int index = 1; index <= values.length; index++) {
                work.step();
                // '**' either consumes no segment (previous row) or one more segment (current row).
                next[index] = part.equals("**") ? previous[index] || next[index - 1]
                        : previous[index - 1] && segment(part, values[index - 1], work);
            }
            previous = next;
        }
        return previous[values.length];
    }

    private static boolean segment(String pattern, String value, Work work) {
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) return pattern.equals(value);
        // '?' consumes one Unicode code point, rather than one half of a surrogate pair.
        int[] characters = value.codePoints().toArray();
        boolean[] previous = new boolean[characters.length + 1];
        previous[0] = true;
        for (int point : pattern.codePoints().toArray()) {
            boolean[] next = new boolean[characters.length + 1];
            next[0] = point == '*' && previous[0];
            for (int index = 1; index <= characters.length; index++) {
                work.step();
                next[index] = point == '*' ? previous[index] || next[index - 1]
                        : previous[index - 1] && (point == '?' || point == characters[index - 1]);
            }
            previous = next;
        }
        return previous[characters.length];
    }

    /** A hard evaluation ceiling fails visibly rather than truncating a policy or a change set. */
    static final class Work {
        private int remaining = 20_000_000;

        void step() {
            if (--remaining < 0) throw new Limit();
        }
    }

    static final class Limit extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
