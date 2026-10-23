package io.github.sjacek.shardpilot;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.testing.Test;

/** Optional settings for Gradle's existing test processes, independent of shard ownership. */
public abstract class TestProcessOptions {
    /** Creates options managed by Gradle's object factory. */
    public TestProcessOptions() { }

    /**
     * Sets the maximum heap of each test JVM, not a total CI or container memory budget.
     * @return an optional positive JVM memory size, such as {@code 512m} or {@code 2g}
     */
    public abstract Property<String> getMaxHeapSize();

    /**
     * Limits simultaneously running test processes for this task.
     * @return an optional positive process count
     */
    public abstract Property<Integer> getMaxParallelForks();

    /**
     * Limits classes per test process; Gradle replaces the process between classes.
     * @return an optional nonnegative class count; zero disables periodic replacement
     */
    public abstract Property<Long> getForkEvery();

    void applyTo(Test task) {
        String heap = getMaxHeapSize().getOrNull();
        Integer forks = getMaxParallelForks().getOrNull();
        Long classes = getForkEvery().getOrNull();
        // Validate the complete block before changing the consumer's task.
        if (heap != null && !heap.matches("[1-9][0-9]*[kKmMgG]?")) {
            throw new InvalidUserDataException("ShardPilot testProcesses.maxHeapSize must be a positive JVM memory size, such as 512m or 2g.");
        }
        if (forks != null && forks < 1) {
            throw new InvalidUserDataException("ShardPilot testProcesses.maxParallelForks must be positive.");
        }
        if (classes != null && classes < 0) {
            throw new InvalidUserDataException("ShardPilot testProcesses.forkEvery must be nonnegative (0 disables periodic replacement).");
        }
        if (heap != null) {
            task.setMaxHeapSize(heap);
            task.getInputs().property("shardpilot.testProcesses.maxHeapSize", heap);
        }
        if (forks != null) {
            task.setMaxParallelForks(forks);
            task.getInputs().property("shardpilot.testProcesses.maxParallelForks", forks);
        }
        if (classes != null) {
            task.setForkEvery(classes);
            task.getInputs().property("shardpilot.testProcesses.forkEvery", classes);
        }
    }
}
