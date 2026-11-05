package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Prepares a common plan without launching tests or creating helper Test tasks. Source-set file
 * dependencies compile the bytecode first. Eligibility, count and history participate in Gradle's
 * input snapshot; a restored cached plan is valid only for those exact inputs.
 * The core owns serialization and cleanup; the adapter only resolves managed Gradle inputs and
 * preserves source-set task dependencies. Independent owning Test tasks need separate output namespaces.
 */
@CacheableTask
public abstract class PrepareShardManifests extends DefaultTask {
    /** Creates a Gradle-managed task. */
    public PrepareShardManifests() { }

    /**
     * Supplies eligible unsharded bytecode.
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
     * Supplies positive partition size.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /**
     * Supplies exactly the history used by the owning task's filter.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getHistory();

    /**
     * Supplies optional validated selection snapshot; absent for the full suite.
     * @return the configured value
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSelectionManifest();

    /**
     * Supplies optional immutable cohort identity produced with the selection.
     * @return the configured value
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSelectionIdentity();

    /**
     * Supplies the execution count used to bind the selected snapshot.
     * @return optional execution partition size; required when selection is configured
     */
    @Optional
    @Input
    public abstract Property<Integer> getSelectionShardCount();

    /**
     * Supplies task-owned plan directory.
     * @return the configured value
     */
    @OutputDirectory
    public abstract DirectoryProperty getDirectory();

    /**
     * Writes sorted baseline and partitions using the same planner as the execution predicate.
     * Validates eligibility/history/count before touching existing evidence. The output directory
     * belongs to this task; cleanup removes only its numeric planned-N.yaml files when count changes.
     * @throws IOException if plan artifacts cannot be written
     */
    @TaskAction
    public void prepare() throws IOException {
        var names = ClassManifest.compiled(getClasses().getFiles(), getClassDirectories().getFiles());
        var history = DurationHistory.parse(getHistory().get());
        if (getSelectionManifest().isPresent()) {
            if (!getShardCount().get().equals(getSelectionShardCount().get())) {
                throw new IllegalArgumentException("Explicit selection planning and execution must use the same shard count");
            }
            var selected = ClassManifest.read(getSelectionManifest().get().getAsFile().toPath(), false);
            if (!names.containsAll(selected)) throw new IllegalArgumentException("Selected classes are outside verification eligibility");
            names = selected;
            history = TestSelection.history(getHistory().get(), names);
        }
        int count = getShardCount().get();
        var directory = getDirectory().get().getAsFile().toPath();
        ShardManifests.prepare(directory, names, history, count);
        if (getSelectionIdentity().isPresent()) {
            java.nio.file.Files.copy(getSelectionIdentity().get().getAsFile().toPath(), directory.resolve("selection.id"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else java.nio.file.Files.deleteIfExists(directory.resolve("selection.id"));
        getLogger().lifecycle("ShardPilot plan: {} eligible classes across {} shards", names.size(), count);
    }
}
