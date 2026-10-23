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
    private boolean verificationConfigured;

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
     * Registers opt-in manifest preparation, actual selection and cross-job coverage verification.
     * The caller supplies an unsharded eligible class tree, including its own discovery restrictions.
     * No application naming convention or CI provider is assumed. Configure this block once per task.
     * Call outside a deferred TaskProvider.configure action, which cannot register sibling tasks.
     * Only the actual-manifest task becomes a test dependency. Preparation and aggregation remain
     * explicit CI steps; an aggregation-only invocation never manufactures missing evidence.
     * @param action eligible bytecode, partition size, artifact directories and verification policy
     */
    public void verification(Action<? super ShardVerificationOptions> action) {
        if (verificationConfigured) throw new IllegalStateException("ShardPilot verification is already configured for " + task.getName());
        verificationConfigured = true;
        ShardVerificationOptions options = objects.newInstance(ShardVerificationOptions.class);
        String directory = "shardpilot/" + task.getName();
        options.getClassDirectories().from(task.getTestClassesDirs());
        options.getShardCount().convention(getShardCount());
        options.getPlannedDirectory().convention(task.getProject().getLayout().getBuildDirectory().dir(directory + "/plan"));
        options.getActualDirectory().convention(task.getProject().getLayout().getBuildDirectory().dir(directory + "/actual"));
        options.getReportFile().convention(task.getProject().getLayout().getBuildDirectory().file(directory + "/coverage.json"));
        options.getHtmlReportFile().convention(task.getProject().getLayout().getBuildDirectory().file(directory + "/coverage.html"));
        options.getVerifyActual().convention(task.getProject().getProviders().gradleProperty("shardpilot.verifyActual")
                .map(Boolean::parseBoolean).orElse(true));
        options.getRequireNonEmptyShards().convention(true);
        action.execute(options);
        ShardVerification.configure(task, this, options);
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
