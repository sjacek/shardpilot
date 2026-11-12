package io.github.sjacek.shardpilot.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Test-only oracle shared by TestKit and Maven Invoker. Native listeners supply terminal events;
 * the planner and class manifests are deliberately not used to decide what should have executed.
 * Java source-file launch mode lets Invoker use this same oracle without a published test library.
 */
public final class VerifyTestExecutions {
    private static final Set<String> OUTCOMES = Set.of("PASSED", "FAILED", "ERROR", "ABORTED", "SKIPPED");

    private VerifyTestExecutions() { }

    /** A native identity and outcome; repeated occurrences remain repeated observations. */
    public record Event(String framework, String kind, String identity, String outcome) { }

    /** Immutable multiset, not a set that could hide a lost or duplicated invocation. */
    public record Snapshot(Map<Event, Integer> occurrences) {
        public Snapshot {
            occurrences = Map.copyOf(occurrences);
        }

        public int executions() {
            return occurrences.entrySet().stream().filter(entry -> entry.getKey().kind().equals("TEST"))
                    .mapToInt(Map.Entry::getValue).sum();
        }

        public Map<String, Integer> outcomes() {
            var result = new TreeMap<String, Integer>();
            occurrences.forEach((event, count) -> {
                if (event.kind().equals("TEST")) result.merge(event.outcome(), count, Integer::sum);
            });
            return result;
        }
    }

    public record Comparison(int baselineExecutions, int shardExecutions, Map<String, Integer> outcomes,
                             List<String> differences) {
        public Comparison {
            outcomes = Map.copyOf(outcomes);
            differences = List.copyOf(differences);
        }

        public boolean passed() {
            return differences.isEmpty();
        }

        public String json() {
            var counts = new ArrayList<String>();
            new TreeMap<>(outcomes).forEach((key, value) -> counts.add(quote(key) + ":" + value));
            return "{\"status\":" + quote(passed() ? "PASSED" : "FAILED")
                    + ",\"baselineExecutions\":" + baselineExecutions + ",\"shardExecutions\":" + shardExecutions
                    + ",\"outcomes\":{" + String.join(",", counts) + "},\"differences\":["
                    + String.join(",", differences.stream().map(VerifyTestExecutions::quote).toList()) + "]}\n";
        }
    }

    /** Read one complete listener stream; a missing stream must never become an empty successful run. */
    public static Snapshot read(Path input) throws IOException {
        var values = new HashMap<Event, Integer>();
        int line = 0;
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            line++;
            String[] fields = row.split("\t", -1);
            if (fields.length != 5 || !fields[0].equals("v1")
                    || !Set.of("junit-platform", "testng").contains(fields[1])
                    || !Set.of("TEST", "CONTAINER").contains(fields[2]) || !OUTCOMES.contains(fields[4])) {
                throw new IllegalArgumentException("Invalid execution event at line " + line + " in " + input);
            }
            String identity = new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.UTF_8);
            if (identity.isBlank() || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(identity.getBytes(StandardCharsets.UTF_8)).equals(fields[3])) {
                throw new IllegalArgumentException("Invalid native identity at line " + line + " in " + input);
            }
            values.merge(new Event(fields[1], fields[2], identity, fields[4]), 1, Integer::sum);
        }
        return new Snapshot(values);
    }

    /** Compare exact identity/outcome multiplicities, and reject identities owned by multiple nodes. */
    public static Comparison compare(Snapshot baseline, List<Snapshot> shards) {
        if (baseline.executions() == 0 || baseline.occurrences().keySet().stream().noneMatch(e -> e.kind().equals("TEST"))) {
            throw new IllegalArgumentException("The native baseline must contain test executions");
        }
        if (shards.size() != 2 && shards.size() != 4) {
            throw new IllegalArgumentException("Execution equivalence requires exactly two or four shards");
        }
        var observed = new HashMap<Event, Integer>();
        var owners = new HashMap<String, Integer>();
        var differences = new ArrayList<String>();
        for (int index = 0; index < shards.size(); index++) {
            for (var entry : shards.get(index).occurrences().entrySet()) {
                Event event = entry.getKey();
                observed.merge(event, entry.getValue(), Integer::sum);
                String identity = event.framework() + "/" + event.kind() + "/" + event.identity();
                Integer owner = owners.putIfAbsent(identity, index);
                if (owner != null && owner != index) differences.add("Identity appears on multiple shards: " + identity);
            }
        }
        var all = new HashSet<>(baseline.occurrences().keySet());
        all.addAll(observed.keySet());
        for (Event event : all) {
            int expected = baseline.occurrences().getOrDefault(event, 0);
            int actual = observed.getOrDefault(event, 0);
            if (expected != actual) differences.add(event + ": expected " + expected + ", observed " + actual);
        }
        differences.sort(String::compareTo);
        return new Comparison(baseline.executions(), new Snapshot(observed).executions(), baseline.outcomes(), differences);
    }

    /** Write evidence before rejecting a differing cohort; callers retain the original input files. */
    public static Comparison verify(Path report, Path baseline, List<Path> shards) throws IOException {
        var inputs = new ArrayList<Path>();
        inputs.add(baseline);
        inputs.addAll(shards);
        for (Path input : inputs) {
            if (report.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                    || Files.exists(report) && Files.exists(input) && Files.isSameFile(report, input)) {
                throw new IllegalArgumentException("The report must not replace execution input");
            }
        }
        Files.deleteIfExists(report);
        for (int index = 0; index < inputs.size(); index++) {
            for (int other = 0; other < index; other++) {
                if (Files.isSameFile(inputs.get(index), inputs.get(other))) {
                    throw new IllegalArgumentException("Execution inputs must be distinct files");
                }
            }
        }
        var observations = new ArrayList<Snapshot>();
        for (Path shard : shards) {
            observations.add(read(shard));
        }
        Comparison result = compare(read(baseline), observations);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, result.json(), StandardCharsets.UTF_8);
        return result;
    }

    /** Arguments: output JSON, native baseline TSV, then exactly two or four native shard TSVs. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 4 && arguments.length != 6) {
            throw new IllegalArgumentException("Expected: report.json baseline.tsv shard-1.tsv shard-2.tsv [shard-3.tsv shard-4.tsv]");
        }
        var paths = new ArrayList<Path>();
        for (int index = 2; index < arguments.length; index++) paths.add(Path.of(arguments[index]));
        Comparison result = verify(Path.of(arguments[0]), Path.of(arguments[1]), paths);
        System.out.print(result.json());
        if (!result.passed()) throw new IllegalStateException("Native executions differ; inspect " + arguments[0]);
    }

    private static String quote(String text) {
        var result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\\' || value == '"') result.append('\\').append(value);
            else if (value < 32) result.append(String.format("\\u%04x", (int) value));
            else result.append(value);
        }
        return result.append('"').toString();
    }
}
