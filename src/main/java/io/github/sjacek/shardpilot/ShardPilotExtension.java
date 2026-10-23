package io.github.sjacek.shardpilot;

import org.gradle.api.Action;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.testing.Test;

import javax.inject.Inject;

/** Per-Test-task settings. Applying the plugin never shards a task without explicit opt-in. */
public abstract class ShardPilotExtension {
    private final Test task;
    private final ObjectFactory objects;

    /**
     * Creates per-task settings managed and instantiated by Gradle.
     * @param task the owning test task
     * @param objects Gradle's object factory
     */
    @Inject
    public ShardPilotExtension(Test task, ObjectFactory objects) {
        this.task = task;
        this.objects = objects;
    }

    /**
     * Explicitly configures Gradle's test processes, even when sharding is disabled.
     * Only supplied values are changed. Providers are resolved after the action returns,
     * during task configuration, before Gradle snapshots inputs or launches a process.
     * Later native Test configuration can override these values in the usual Gradle order.
     * @param action optional heap, parallel process count and process replacement settings
     */
    public void testProcesses(Action<? super TestProcessOptions> action) {
        TestProcessOptions options = objects.newInstance(TestProcessOptions.class);
        action.execute(options);
        options.applyTo(task);
    }

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
