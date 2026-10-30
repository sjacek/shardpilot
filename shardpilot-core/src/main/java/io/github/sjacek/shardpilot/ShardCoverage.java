package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validates a CI artifact cohort against its supplied baseline, independently of Gradle execution.
 * The compile job owns that baseline: this check cannot establish artifact provenance or prove
 * successful execution of methods. CI must still require all normal test-result gates.
 */
public final class ShardCoverage {
    private ShardCoverage() { }

    /**
     * Requires an exact partition of the baseline and, optionally, the same selection on every node.
     * Matching only the union would miss swapped node artifacts, so compare each index separately.
     * Extra indexed artifacts fail even if the expected ones match, detecting leftovers from other runs.
     * @param plannedDirectory baseline and planned artifacts
     * @param actualDirectory actual node artifacts
     * @param count expected partition count
     * @param verifyActual whether actual selections are required
     * @param requireNonEmpty whether each shard must be nonempty
     * @return successful compact JSON report
     * @throws IOException if evidence cannot be read
     */
    public static String verify(Path plannedDirectory, Path actualDirectory, int count,
                         boolean verifyActual, boolean requireNonEmpty) throws IOException {
        Evaluation result = evaluate(plannedDirectory, actualDirectory, count, verifyActual, requireNonEmpty);
        if (result.failure() instanceof IOException exception) throw exception;
        if (result.failure() instanceof IllegalArgumentException exception) throw exception;
        return result.json();
    }

    /**
     * Reads each expected manifest once so JSON, HTML and the gate describe the same evidence.
     * @param plannedDirectory baseline and planned artifacts
     * @param actualDirectory actual node artifacts
     * @param count expected partition count
     * @param verifyActual whether actual selections are required
     * @param requireNonEmpty whether each shard must be nonempty
     * @return evaluation retaining failures and available evidence
     */
    public static Evaluation evaluate(Path plannedDirectory, Path actualDirectory, int count,
                               boolean verifyActual, boolean requireNonEmpty) {
        Manifest baseline = read(plannedDirectory.resolve("baseline.yaml"), false);
        var planned = new TreeMap<Integer, Manifest>();
        var actual = new TreeMap<Integer, Manifest>();
        for (int index = 1; index <= count; index++) {
            planned.put(index, read(plannedDirectory.resolve("planned-" + index + ".yaml"), !requireNonEmpty));
            if (verifyActual) actual.put(index, read(actualDirectory.resolve("actual-" + index + ".yaml"), !requireNonEmpty));
        }
        try {
            String json = validate(plannedDirectory, actualDirectory, count, verifyActual, baseline, planned, actual);
            return new Evaluation(json, null, baseline, Map.copyOf(planned), Map.copyOf(actual), verifyActual);
        } catch (IllegalArgumentException | IOException exception) {
            return new Evaluation(failed(exception.getMessage()), exception, baseline,
                    Map.copyOf(planned), Map.copyOf(actual), verifyActual);
        }
    }

    /**
     * A failed read retains its diagnostic rather than pretending an unavailable shard is empty.
     * @param classes valid names, empty when reading failed
     * @param failure read/validation error, or null for a valid document
     */
    public record Manifest(Set<String> classes, Exception failure) {
        /** Copies the supplied names so the snapshot cannot be changed through its input collection. */
        public Manifest { classes = Set.copyOf(classes); }

        Set<String> require() throws IOException {
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof IllegalArgumentException exception) throw exception;
            return classes;
        }
    }

    /**
     * Immutable evidence shared by both report formats, including partial evidence on failure.
     * @param json compact success/failure report
     * @param failure global validation error, or null on success
     * @param baseline baseline evidence
     * @param planned index-specific planned evidence
     * @param actual index-specific actual evidence, empty in plan-only mode
     * @param verifyActual whether actual comparison was requested
     */
    public record Evaluation(String json, Exception failure, Manifest baseline, Map<Integer, Manifest> planned,
                      Map<Integer, Manifest> actual, boolean verifyActual) {
        /** Copies both index maps to retain a stable report snapshot. */
        public Evaluation {
            planned = Map.copyOf(planned);
            actual = Map.copyOf(actual);
        }
    }

    private static Manifest read(Path path, boolean allowEmpty) {
        try {
            return new Manifest(Set.copyOf(ClassManifest.read(path, allowEmpty)), null);
        } catch (IllegalArgumentException | IOException exception) {
            return new Manifest(Set.of(), exception);
        }
    }

    private static String validate(Path plannedDirectory, Path actualDirectory, int count, boolean verifyActual,
                                   Manifest baselineManifest, Map<Integer, Manifest> plans,
                                   Map<Integer, Manifest> selections) throws IOException {
        ShardPlan.validateShard(count, 1);
        Set<String> baseline = baselineManifest.require();
        var seen = new TreeSet<String>();
        var shards = new ArrayList<String>();
        for (int index = 1; index <= count; index++) {
            Set<String> planned = plans.get(index).require();
            var overlap = new TreeSet<>(planned);
            overlap.retainAll(seen);
            if (!overlap.isEmpty()) throw new IllegalArgumentException("Classes assigned to multiple shards: " + overlap);
            seen.addAll(planned);
            if (verifyActual) {
                var actual = selections.get(index).require();
                if (!actual.equals(planned)) {
                    throw new IllegalArgumentException("Shard " + index + " differs from plan: missing="
                            + difference(planned, actual) + ", unexpected=" + difference(actual, planned));
                }
            }
            shards.add("{\"index\":" + index + ",\"classes\":" + planned.size() + ",\"sha256\":\"" + digest(planned) + "\"}");
        }
        if (!seen.equals(baseline)) {
            throw new IllegalArgumentException("Incomplete partition: missing=" + difference(baseline, seen)
                    + ", unexpected=" + difference(seen, baseline));
        }
        requireExactManifests(plannedDirectory, "planned", count);
        if (verifyActual) requireExactManifests(actualDirectory, "actual", count);
        return "{\"status\":\"passed\",\"classes\":" + baseline.size() + ",\"actualVerified\":" + verifyActual
                + ",\"shards\":[" + String.join(",", shards) + "]}\n";
    }

    /**
     * Escapes even invalid filenames/class names so failed validation still produces valid JSON.
     * @param message validation diagnostic
     * @return compact failure report
     */
    public static String failed(String message) {
        return "{\"status\":\"failed\",\"error\":" + quote(message) + "}\n";
    }

    /**
     * Writes both formats from one snapshot, including validation failures. Callers retain their
     * build-tool-specific failure outcome; file I/O is not an atomic multi-output transaction.
     * Normalized absolute output paths must differ, and this check happens before either write.
     * JSON is written first: an HTML write failure can leave valid JSON behind, but is still an
     * I/O failure for the caller. Only a returned evaluation means both writes completed.
     * @param plannedDirectory baseline and planned artifacts
     * @param actualDirectory actual node artifacts
     * @param count expected partition count
     * @param verifyActual whether actual selections are required
     * @param requireNonEmpty whether each shard must be nonempty
     * @param jsonFile JSON output path
     * @param htmlFile distinct HTML output path
     * @return the evaluation written to both reports
     * @throws IOException if either report cannot be written
     */
    public static Evaluation writeReports(Path plannedDirectory, Path actualDirectory, int count,
                                          boolean verifyActual, boolean requireNonEmpty,
                                          Path jsonFile, Path htmlFile) throws IOException {
        if (jsonFile.toAbsolutePath().normalize().equals(htmlFile.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("JSON and HTML report paths must be distinct");
        }
        var result = evaluate(plannedDirectory, actualDirectory, count, verifyActual, requireNonEmpty);
        Files.createDirectories(jsonFile.toAbsolutePath().getParent());
        Files.writeString(jsonFile, result.json(), StandardCharsets.UTF_8);
        Files.createDirectories(htmlFile.toAbsolutePath().getParent());
        Files.writeString(htmlFile, ShardCoverageHtml.render(result), StandardCharsets.UTF_8);
        return result;
    }

    private static void requireExactManifests(Path directory, String prefix, int count) throws IOException {
        var expected = new TreeSet<String>();
        for (int index = 1; index <= count; index++) expected.add(prefix + "-" + index + ".yaml");
        try (var files = Files.list(directory)) {
            var found = new TreeSet<>(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix + "-") && name.endsWith(".yaml")).toList());
            if (!found.equals(expected)) throw new IllegalArgumentException("Unexpected " + prefix + " manifest set: " + found);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        var result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    /** Hashes sorted UTF-8 names joined by LF, without a trailing LF; independent of input line endings. */
    private static String digest(Set<String> classes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", new TreeSet<>(classes)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static String quote(String text) {
        var escaped = new StringBuilder("\"");
        for (char character : text.toCharArray()) {
            if (character == '"' || character == '\\') escaped.append('\\').append(character);
            else if (character < 32) escaped.append(String.format("\\u%04x", (int) character));
            else escaped.append(character);
        }
        return escaped.append('"').toString();
    }
}
