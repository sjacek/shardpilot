package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Validates candidate equality before Test can become NO-SOURCE. A doFirst-only gate is skipped
 * when native file exclusions remove every selected class. This read-only guard works without
 * coverage configuration and never manufactures actual evidence or launches a test worker.
 */
@DisableCachingByDefault(because = "Read-only guard runs before Test's NO-SOURCE check")
public abstract class ValidateTestSelection extends DefaultTask {
    /** Creates the Gradle-managed guard. */
    public ValidateTestSelection() { }

    /**
     * Supplies candidates after subset and ownership exclusions.
     * @return candidate bytecode; empty input must still be checked
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /**
     * Supplies compilation roots for binary identities.
     * @return configured roots
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Supplies the validated selected baseline.
     * @return snapshot and its producer dependency
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSelectionManifest();

    /**
     * Supplies immutable timing metadata.
     * @return history contents
     */
    @Input
    public abstract Property<String> getHistory();

    /**
     * Supplies total partitions.
     * @return positive shard count
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /**
     * Supplies current partition.
     * @return one-based shard index
     */
    @Input
    public abstract Property<Integer> getShardIndex();

    /**
     * Fails mismatched native class filters even when no Test actions would execute.
     * @throws IOException if the validated snapshot cannot be read
     */
    @TaskAction
    public void validate() throws IOException {
        ShardPlan.validateShard(getShardCount().get(), getShardIndex().get());
        var selected = ClassManifest.read(getSelectionManifest().get().getAsFile().toPath(), false);
        var plan = ShardPlan.create(TestSelection.history(getHistory().get(), selected), getShardCount().get());
        selected.removeIf(name -> plan.shardFor(name) != getShardIndex().get());
        var actual = ClassManifest.compiled(getClasses().getFiles(), getClassDirectories().getFiles());
        if (!actual.equals(selected)) {
            throw new GradleException("Native class filters differ from the explicit selection; align eligibleClasses with discovery");
        }
    }
}
