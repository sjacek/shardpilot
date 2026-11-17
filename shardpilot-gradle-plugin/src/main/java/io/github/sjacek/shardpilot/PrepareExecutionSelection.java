package io.github.sjacek.shardpilot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.jspecify.annotations.NullMarked;

/**
 * Prepares C2 selection v2 from retained C5/C3 artifacts without scheduling Git, compilation or tests.
 * File properties are metadata-only: core bounds reads and validates aliases before Gradle can walk
 * an untrusted snapshot. Use already retained paths instead of wiring these properties to producer
 * outputs: their inputs must be available without scheduling compilation or tests.
 */
@DisableCachingByDefault(because = "Execution selection must replay retained C5 inputs and rehash optional C3 bytes")
@NullMarked
public abstract class PrepareExecutionSelection extends ExecutionArtifactTask {
    /** Creates an untracked artifact gate, with common CLI conventions but no producer dependencies. */
    public PrepareExecutionSelection() {
        doNotTrackState("Validate retained selection and artifact boundaries before any Gradle file snapshots");
        var providers = getProject().getProviders();
        var layout = getProject().getLayout();
        getProfile().convention(providers.gradleProperty("shardpilot.selectionProfile"));
        getRevision().convention(providers.gradleProperty("shardpilot.executionRevision"));
        getSuite().convention(providers.gradleProperty("shardpilot.selectionSuite").orElse(getProfile().map(ProfileSelection::defaultSuite)));
        getShardCount().convention(providers.gradleProperty("shardpilot.count").map(Integer::valueOf).orElse(1));
        getSnapshotDirectory().convention(layout.dir(providers.gradleProperty("shardpilot.selectionSnapshotDirectory").map(File::new)));
        getPolicyFile().convention(layout.file(providers.gradleProperty("shardpilot.selectionPolicyFile").map(File::new)));
        getEligibilityFile().convention(layout.file(providers.gradleProperty("shardpilot.executionEligibilityFile").map(File::new)));
        getHistoryFile().convention(layout.file(providers.gradleProperty("shardpilot.executionHistoryFile").map(File::new)));
        getExplicitManifestFile().convention(layout.file(providers.gradleProperty("shardpilot.selectionManifestFile").map(File::new)));
        getSelectionFile().convention(layout.getBuildDirectory().file("shardpilot/execution-selection.json"));
    }

    /** Retained C5 snapshot.
     * @return complete retained C5 snapshot
     */
    @Internal
    public abstract DirectoryProperty getSnapshotDirectory();

    /** Reviewed selection policy.
     * @return reviewed policy used to replay the frozen decision
     */
    @Internal
    public abstract RegularFileProperty getPolicyFile();

    /** Independent native eligibility.
     * @return full independent native baseline
     */
    @Internal
    public abstract RegularFileProperty getEligibilityFile();

    /** Optional original duration history.
     * @return original timing bytes; unset means empty history
     */
    @Internal
    public abstract RegularFileProperty getHistoryFile();

    /** Optional original explicit requests.
     * @return original explicit requests, only for an explicit profile
     */
    @Internal
    public abstract RegularFileProperty getExplicitManifestFile();

    /** Policy profile identifier.
     * @return chosen policy profile
     */
    @Input
    public abstract Property<String> getProfile();

    /** Independently supplied source revision.
     * @return full source revision from trusted CI metadata
     */
    @Input
    public abstract Property<String> getRevision();

    /** Selected-suite identity.
     * @return expected selected-suite namespace
     */
    @Input
    public abstract Property<String> getSuite();

    /** Common partition count.
     * @return common number of partitions
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /** Generated executable selection document.
     * @return canonical selection v2 output outside all retained input trees
     */
    @OutputFile
    public abstract RegularFileProperty getSelectionFile();

    /** Metadata-only input description.
     * @return metadata-only locations; core validates actual bytes and aliases on every invocation
     */
    @Input
    public List<String> getInputLocations() {
        var values = new ArrayList<>(List.of(getSnapshotDirectory().get().getAsFile().getAbsolutePath(),
                getPolicyFile().get().getAsFile().getAbsolutePath(), getEligibilityFile().get().getAsFile().getAbsolutePath()));
        if (getHistoryFile().isPresent()) values.add(getHistoryFile().get().getAsFile().getAbsolutePath());
        if (getExplicitManifestFile().isPresent()) values.add(getExplicitManifestFile().get().getAsFile().getAbsolutePath());
        return values;
    }

    @Override
    protected void compiledConventions(CompiledArtifactOptions options) {
        // These are trusted producer parameters. The aggregate task deliberately does not default them
        // from the producer's selection document or downloaded C3 metadata.
        options.getRevision().convention(getRevision()); options.getSuite().convention(getSuite());
    }

    /**
     * Writes fresh canonical evidence after ownership validation; replay failures revoke the prior output.
     * Configuration/ownership errors may fail earlier, so consumers must require this invocation's outcome.
     * @throws IOException if the generated output cannot be replaced
     */
    @TaskAction
    public void prepare() throws IOException {
        var inputs = new ExecutionSelection.Inputs(getSnapshotDirectory().get().getAsFile().toPath(), getPolicyFile().get().getAsFile().toPath(),
                getEligibilityFile().get().getAsFile().toPath(), getHistoryFile().isPresent() ? getHistoryFile().get().getAsFile().toPath() : null,
                getExplicitManifestFile().isPresent() ? getExplicitManifestFile().get().getAsFile().toPath() : null,
                getProfile().get(), getRevision().get(), getSuite().get(), getShardCount().get(), compiledInputs());
        String hash = ExecutionSelection.write(inputs, getSelectionFile().get().getAsFile().toPath());
        getLogger().lifecycle("ShardPilot execution selection SHA-256: {}", hash);
    }
}
