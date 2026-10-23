package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Framework-independent preparation and class selection shared by the Gradle and Maven adapters.
 * Adapters establish eligibility and validate binary names; this boundary never loads consumer
 * classes or interprets native runner filters. Both operations use the same family-level planner.
 */
public final class ShardManifests {
    private ShardManifests() { }

    /**
     * Validates inputs before modifying the task-owned plan; cleans only numeric planned files.
     * Validation covers nonempty eligibility, duration metadata and partition count. Candidate
     * names must already be validated by the adapter. Writes are not an atomic directory swap:
     * an I/O failure requires fresh preparation, not reuse of a partially replaced cohort.
     * @param directory plan output namespace
     * @param names validated, unsharded candidate names
     * @param history TSV duration contents, or empty for hash-only assignment
     * @param count positive partition count
     * @throws IOException if artifacts cannot be written
     */
    public static void prepare(Path directory, Set<String> names, String history, int count) throws IOException {
        if (names.isEmpty()) throw new IllegalArgumentException("ShardPilot verification requires nonempty eligibleClasses");
        var plan = ShardPlan.create(DurationHistory.parse(history), count);
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (var file : files.filter(path -> path.getFileName().toString().matches("planned-[0-9]+\\.yaml")).toList()) {
                Files.delete(file);
            }
        }
        ClassManifest.write(directory.resolve("baseline.yaml"), names);
        for (int index = 1; index <= count; index++) {
            ClassManifest.write(directory.resolve("planned-" + index + ".yaml"), selected(names, plan, index));
        }
    }

    /**
     * Selects one-based class-family ownership without loading consumer bytecode.
     * The returned set is a new collection; modifying it does not alter the supplied eligibility.
     * It is computed from current inputs rather than read from a prepared manifest. CI aggregation
     * compares independently recorded selections against the common plan.
     * @param names unsharded candidate names
     * @param history TSV duration contents
     * @param count positive partition count
     * @param index one-based selected partition
     * @return sorted selected names
     */
    public static Set<String> select(Set<String> names, String history, int count, int index) {
        ShardPlan.validateShard(count, index);
        return selected(names, ShardPlan.create(DurationHistory.parse(history), count), index);
    }

    private static Set<String> selected(Set<String> names, ShardPlan plan, int index) {
        var selected = new TreeSet<String>();
        for (String name : names) if (plan.shardFor(name) == index) selected.add(name);
        return selected;
    }
}
