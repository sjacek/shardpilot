package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Records the real candidate tree after native and ShardPilot exclusions, before test execution.
 * A candidate is bytecode selected by Gradle, not a promise that an engine or CLI method filter
 * executes it. Count/index are inputs even when two partitions happen to select identical files.
 */
@CacheableTask
public abstract class WriteShardManifest extends DefaultTask {
    /** Creates a Gradle-managed task. */
    public WriteShardManifest() { }

    /**
     * Supplies actual candidate bytecode.
     * @return the configured value
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /**
     * Supplies bytecode roots.
     * @return the configured value
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Supplies current execution partition size.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /**
     * Supplies one-based execution index.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getShardIndex();

    /**
     * Supplies index-specific output artifact.
     * @return the configured value
     */
    @OutputFile
    public abstract RegularFileProperty getManifest();

    /**
     * Persists sorted class names, rejecting duplicate or out-of-root bytecode.
     * @throws IOException if the candidate artifact cannot be written
     */
    @TaskAction
    public void writeManifest() throws IOException {
        ShardPlan.validateShard(getShardCount().get(), getShardIndex().get());
        var names = ClassManifest.compiled(getClasses().getFiles(), getClassDirectories().getFiles());
        ClassManifest.write(getManifest().get().getAsFile().toPath(), names);
        getLogger().lifecycle("ShardPilot actual selection: shard {}, {} classes", getShardIndex().get(), names.size());
    }
}
