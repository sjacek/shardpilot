package io.github.sjacek.shardpilot;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Per-Test-task settings. Applying the plugin never shards a task without explicit opt-in. */
public abstract class ShardPilotExtension {
    /** Creates settings managed and instantiated by Gradle. */
    public ShardPilotExtension() { }

    /**
     * Controls opt-in for this task.
     * @return whether this test task participates in sharding; defaults to false
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Configures the size of the CI partition.
     * @return total shards, defaulting to the shardpilot.count Gradle property, then 1
     */
    public abstract Property<Integer> getShardCount();

    /**
     * Selects this job's partition.
     * @return one-based shard index, defaulting to the shardpilot.index Gradle property, then 1
     */
    public abstract Property<Integer> getShardIndex();

    /**
     * Supplies immutable timing metadata shared by all jobs in a run.
     * @return optional UTF-8 TSV history; unset or missing history uses hash-only assignment
     */
    public abstract RegularFileProperty getDurationsFile();
}
