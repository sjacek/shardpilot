package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Validates selection before candidate filtering; only managed inputs survive configuration caching. */
@CacheableTask
public abstract class PrepareTestSelection extends DefaultTask {
    /** Creates the Gradle-managed producer. */
    public PrepareTestSelection() {
        // Frozen input must verify the current checkout even when bytecode/report bytes are unchanged.
        // Both checks matter: a worker must neither skip restoration nor restore its result from cache.
        getOutputs().upToDateWhen(task -> !((PrepareTestSelection) task).getSnapshotDirectory().isPresent());
        getOutputs().doNotCacheIf("Frozen profile validates the local checkout on every invocation",
                task -> ((PrepareTestSelection) task).getSnapshotDirectory().isPresent());
    }

    /**
     * Supplies the legacy manifest or explicit-profile requests, fingerprinted by contents.
     * Other profile modes obtain their selected manifest from the common engine instead.
     * @return the configured value
     */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    /**
     * Supplies optional common version-1 policy.
     * @return the configured value
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPolicyFile();

    /**
     * Supplies profile paired with policyFile.
     * @return the configured value
     */
    @Optional
    @Input
    public abstract Property<String> getProfile();

    /**
     * Supplies freshly acquired immutable Git metadata; absent when restoring a frozen cohort.
     * @return the configured value
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getChangesFile();

    /**
     * Supplies optional read-only downloaded selection.
     * @return the configured value
     */
    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.NONE)
    public abstract DirectoryProperty getSnapshotDirectory();

    /**
     * Supplies worker checkout, checked during noncacheable snapshot restoration.
     * @return the configured value
     */
    @Internal
    public abstract DirectoryProperty getCheckout();

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
        if (getPolicyFile().isPresent() || getProfile().isPresent() || getSnapshotDirectory().isPresent()) {
            prepareProfile(directory);
            return;
        }
        if (!getManifestFile().isPresent()) throw new IllegalArgumentException("Selection requires a manifest or a policy/profile pair");
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

    private void prepareProfile(java.nio.file.Path directory) throws IOException {
        if (!getPolicyFile().isPresent() || !getProfile().isPresent()) {
            throw new IllegalArgumentException("selectionPolicyFile and selectionProfile must be supplied together");
        }
        for (var file : java.util.List.of(getPolicyFile(), getManifestFile())) {
            if (!file.isPresent()) continue;
            var source = file.get().getAsFile().toPath();
            if (source.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Keep maintained profile inputs outside generated selection outputs");
            }
            TestSelection.protectInput(source, directory.resolve("selected.yaml"), directory.resolve("eligible.yaml"),
                    directory.resolve("selection.id"), directory.resolve("selection.json"), directory.resolve("selection.html"),
                    directory.resolve("decision/selected.yaml"), directory.resolve("decision/eligible.yaml"), directory.resolve("decision/decision.id"),
                    directory.resolve("decision/decision.json"), directory.resolve("decision/decision.html"), directory.resolve("decision/git.json"));
        }
        if (getSnapshotDirectory().isPresent()) ProfileSelection.protectSnapshot(getSnapshotDirectory().get().getAsFile().toPath(), directory.toAbsolutePath().getParent());
        ProfileSelection.invalidate(directory);
        var policy = SelectionPolicyYaml.read(getPolicyFile().get().getAsFile().toPath());
        var eligible = ClassManifest.compiled(getClasses().getFiles(), getClassDirectories().getFiles());
        var explicit = getManifestFile().isPresent() ? ClassManifest.read(getManifestFile().get().getAsFile().toPath(), false) : java.util.Set.<String>of();
        if (getSnapshotDirectory().isPresent()) {
            ProfileSelection.restore(getSnapshotDirectory().get().getAsFile().toPath(), directory, policy, getProfile().get(), eligible,
                    explicit, getSuite().get(), getHistory().get(), getShardCount().get(), getCheckout().get().getAsFile().toPath());
        } else {
            var changes = GitChanges.read(getChangesFile().get().getAsFile().toPath());
            var context = new SelectionContext(getProfile().get(), eligible, changes.changes(), DurationHistory.parse(getHistory().get()), explicit);
            ProfileSelection.prepare(directory, policy, context, changes, getSuite().get(), getHistory().get(), getShardCount().get());
        }
    }
}
