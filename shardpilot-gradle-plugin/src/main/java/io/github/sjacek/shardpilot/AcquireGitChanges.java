package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Acquires mutable Git/CI inputs at execution time; downstream planning caches the frozen output bytes. */
@DisableCachingByDefault(because = "Git refs, working-tree state and CI hints must be observed on every planning invocation")
public abstract class AcquireGitChanges extends DefaultTask {
    /**
     * Creates an execution-time producer. Ref tips and CI hints are not compiled-class inputs, so
     * observing Git must not become UP-TO-DATE merely because Test's bytecode stayed unchanged.
     * No Git process is run while Gradle stores its configuration cache.
     */
    public AcquireGitChanges() { getOutputs().upToDateWhen(task -> false); }

    /**
     * Supplies maintained policy used only to determine whether this mode needs Git.
     * @return the configured value
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPolicyFile();

    /**
     * Supplies selected policy profile.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getProfile();

    /**
     * Supplies checkout directory; its mutable contents are deliberately not build-cache inputs.
     * @return the configured value
     */
    @Internal
    public abstract DirectoryProperty getCheckout();

    /**
     * Supplies explicit base or empty for CI inference.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getBase();

    /**
     * Supplies commit-ish identifying checked-out HEAD.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getHead();

    /**
     * Supplies whether to compare the common ancestor.
     * @return the configured value
     */
    @Input
    public abstract Property<Boolean> getMergeBase();

    /**
     * Supplies explicit fetch permission.
     * @return the configured value
     */
    @Input
    public abstract Property<Boolean> getFetch();

    /**
     * Supplies whether Gradle was invoked offline.
     * @return the configured value
     */
    @Input
    public abstract Property<Boolean> getOffline();

    /**
     * Supplies configured remote name.
     * @return the configured value
     */
    @Input
    public abstract Property<String> getRemote();

    /**
     * Supplies requested bounded fetch depth.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getFetchDepth();

    /**
     * Supplies per-command timeout in seconds.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getTimeoutSeconds();

    /**
     * Supplies deterministic acquisition snapshot consumed by the selection producer.
     * @return the configured value
     */
    @OutputFile
    public abstract RegularFileProperty getSnapshot();

    /**
     * Captures exact revisions/paths or explicit UNKNOWN evidence. The pure engine chooses FULL/FAIL.
     * @throws IOException if maintained policy or output cannot be accessed
     */
    @TaskAction
    public void acquire() throws IOException {
        TestSelection.protectInput(getPolicyFile().get().getAsFile().toPath(), getSnapshot().get().getAsFile().toPath());
        var policy = SelectionPolicyYaml.read(getPolicyFile().get().getAsFile().toPath());
        var profile = policy.profiles().get(getProfile().get());
        // An unknown profile is left for the engine's failed decision; it must never trigger implicit
        // affected-mode I/O. Non-affected modes work even outside a Git checkout.
        var result = profile != null && profile.mode() == SelectionPolicy.Mode.AFFECTED
                ? GitChanges.acquire(new GitChangeOptions(getCheckout().get().getAsFile().toPath(), getBase().get(), getHead().get(),
                    getMergeBase().get(), getFetch().get() && !getOffline().get(), getRemote().get(), getFetchDepth().get(), getTimeoutSeconds().get()),
                    System.getenv()) : GitChanges.notRequired();
        var output = getSnapshot().get().getAsFile().toPath();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, result.json());
        getLogger().lifecycle("ShardPilot Git input: {} / {}", result.source(), result.failure());
    }
}
