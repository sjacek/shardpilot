package io.github.sjacek.shardpilot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic longest-processing-time packing with hash fallback for unmeasured classes. */
public final class ShardPlan {
    private final int shardCount;
    private final Map<String, Integer> assignments;
    private final Map<String, Double> durations;

    private ShardPlan(int shardCount, Map<String, Integer> assignments, Map<String, Double> durations) {
        this.shardCount = shardCount;
        this.assignments = Map.copyOf(assignments);
        this.durations = Map.copyOf(durations);
    }

    /**
     * Packs measured class families, longest first; equal weights use class name, then lowest shard.
     * Nested classes stay with their outer class, including when XML timings list them separately.
     *
     * @param measuredSeconds finite, nonnegative class durations
     * @param shardCount positive number of independent CI shards
     * @return an immutable plan with one-based shard indices
     */
    public static ShardPlan create(Map<String, Double> measuredSeconds, int shardCount) {
        validateShard(shardCount, 1);
        var families = new TreeMap<String, Double>();
        measuredSeconds.forEach((name, seconds) -> {
            if (name == null || name.isBlank() || seconds == null || !Double.isFinite(seconds) || seconds < 0) {
                throw new IllegalArgumentException("ShardPilot durations must have names and finite, nonnegative values");
            }
        });
        // Floating-point addition depends on order. Sort nested records before merging so every JVM
        // computes identical weights, even when an immutable map randomizes its iteration order.
        measuredSeconds.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> families.merge(DurationHistory.family(entry.getKey()), entry.getValue(), Double::sum));
        if (families.values().stream().anyMatch(seconds -> !Double.isFinite(seconds))) {
            throw new IllegalArgumentException("ShardPilot class family duration overflow");
        }
        // No more than one nonempty bin per family is needed, even with a very large shard count.
        double[] loads = new double[Math.min(shardCount, families.size())];
        var assignments = new HashMap<String, Integer>();
        families.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    int target = 0;
                    for (int candidate = 1; candidate < loads.length; candidate++) {
                        if (loads[candidate] < loads[target]) target = candidate;
                    }
                    loads[target] += entry.getValue();
                    if (!Double.isFinite(loads[target])) {
                        throw new IllegalArgumentException("ShardPilot shard duration overflow");
                    }
                    assignments.put(entry.getKey(), target + 1);
                });
        return new ShardPlan(shardCount, assignments, families);
    }

    /**
     * Finds the owner of a measured or unmeasured class.
     * @param className binary class name, including any nested-class suffix
     * @return one-based shard index
     */
    public int shardFor(String className) {
        String name = DurationHistory.family(className);
        return assignments.getOrDefault(name, Math.floorMod(name.hashCode(), shardCount) + 1);
    }

    /**
     * Reports known work only; excludes unmeasured classes and JVM/container startup.
     * Family names define the summation order so randomized immutable-map iteration cannot
     * change the last bits of a recorded load between JVMs or the resulting report JSON.
     * @param shardIndex one-based shard index
     * @return total recorded duration in seconds
     */
    public double recordedSeconds(int shardIndex) {
        validateShard(shardCount, shardIndex);
        return durations.entrySet().stream()
                .filter(entry -> assignments.get(entry.getKey()) == shardIndex)
                .sorted(Map.Entry.comparingByKey())
                .mapToDouble(Map.Entry::getValue).sum();
    }

    /**
     * Counts the measured families included in the plan.
     * @return the number of measured outer-class families
     */
    public int recordedFamilies() {
        return assignments.size();
    }

    /**
     * Rejects invalid settings instead of silently selecting the wrong test subset.
     * @param shardCount positive number of shards
     * @param shardIndex one-based shard index
     * @throws IllegalArgumentException if the index or count is invalid
     */
    public static void validateShard(int shardCount, int shardIndex) {
        if (shardIndex < 1 || shardIndex > shardCount) {
            throw new IllegalArgumentException("ShardPilot shard index must be between 1 and shard count (count="
                    + shardCount + ", index=" + shardIndex + ")");
        }
    }
}
