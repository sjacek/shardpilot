package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Validates selection before candidate filtering; only managed inputs survive configuration caching. */
@CacheableTask
public abstract class PrepareTestSelection extends DefaultTask {
    /** Creates the Gradle-managed producer. */
    public PrepareTestSelection() { }

    /**
     * Supplies required consumer manifest, with its contents included in the task fingerprint.
     * @return the configured value
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    /**
     * Supplies independently discovered full eligible bytecode.
     * @return the configured value
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /**
     * Supplies bytecode roots used for binary identities.
     * @return the configured value
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Supplies safe suite identity.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getSuite();

    /**
     * Supplies immutable history text.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getHistory();

    /**
     * Supplies total partitions.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /**
     * Supplies suite-owned diagnostics and validated selection snapshot.
     * @return the configured value
     */
    @OutputDirectory
    public abstract DirectoryProperty getDirectory();

    /**
     * Validates before publishing the selected baseline. Invalid input cannot fall back to full.
     * @throws IOException if a manifest or report cannot be read/written
     */
    @TaskAction
    public void prepare() throws IOException {
        var directory = getDirectory().get().getAsFile().toPath();
        if (getManifestFile().get().getAsFile().toPath().toAbsolutePath().normalize()
                .startsWith(directory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Keep the selection manifest outside generated selection outputs");
        }
        // Check outside the failure-report handler: diagnostics must not overwrite the input either.
        TestSelection.protectInput(getManifestFile().get().getAsFile().toPath(), directory.resolve("selected.yaml"),
                directory.resolve("eligible.yaml"), directory.resolve("selection.id"), directory.resolve("selection.json"),
                directory.resolve("selection.html"));
        try {
            var eligible = ClassManifest.compiled(getClasses().getFiles(), getClassDirectories().getFiles());
            var selection = TestSelection.read(getManifestFile().get().getAsFile().toPath(), eligible, getSuite().get());
            selection.write(directory, getHistory().get(), getShardCount().get());
        } catch (IllegalArgumentException failure) {
            TestSelection.writeFailure(directory, failure.getMessage());
            throw failure;
        }
    }
}
