package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ExecutionSelection;
import io.github.sjacek.shardpilot.ProfileSelection;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Artifact-only C5/C2 bridge. No default phase, dependency resolution, Git or compilation is required. */
@Mojo(name = "prepare-execution-selection", threadSafe = true, requiresProject = false)
@NullMarked
public final class PrepareExecutionSelectionMojo extends AbstractExecutionArtifactMojo {
    /** Creates Maven-managed configuration without reading any retained artifacts. */
    public PrepareExecutionSelectionMojo() { }

    /** Complete frozen C5 selection from a trusted planning job. */
    @Parameter(property = "shardpilot.selectionSnapshotDirectory", required = true)
    private File snapshotDirectory;

    /** Current reviewed C5 policy. */
    @Parameter(property = "shardpilot.selectionPolicyFile", required = true)
    private File policyFile;

    /** Independent complete native baseline. */
    @Parameter(property = "shardpilot.executionEligibilityFile", required = true)
    private File eligibilityFile;

    /** Exact original history; unset means empty. */
    @Parameter(property = "shardpilot.executionHistoryFile")
    private @Nullable File historyFile;

    /** Original requested manifest for an explicit profile. */
    @Parameter(property = "shardpilot.selectionManifestFile")
    private @Nullable File explicitManifestFile;

    /** Active C5 policy profile. */
    @Parameter(property = "shardpilot.selectionProfile", required = true)
    private String profile;

    /** Full source revision supplied independently by CI. */
    @Parameter(property = "shardpilot.executionRevision", required = true)
    private String revision;

    /** Selected-suite override; defaults to profile-NAME. */
    @Parameter(property = "shardpilot.selectionSuite", defaultValue = "")
    private @Nullable String suite = "";

    /** Common partition count. */
    @Parameter(property = "shardpilot.count", defaultValue = "1")
    private int shardCount = 1;

    /** Canonical selection v2 output outside all retained input trees. */
    @Parameter(property = "shardpilot.executionSelectionFile", defaultValue = "${project.build.directory}/shardpilot/execution-selection.json", required = true)
    private File selectionFile;

    /**
     * Replays retained inputs without attaching a native lifecycle. Domain failures are build-gate
     * failures; output I/O failures are execution errors. Neither exception retains filesystem/parser
     * causes that could expose private input fragments.
     * @throws MojoExecutionException if the owned output cannot be written
     * @throws MojoFailureException if configuration or retained selection/C3 evidence is invalid
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            String selectedSuite = suite == null || suite.isEmpty() ? ProfileSelection.defaultSuite(profile) : suite;
            var inputs = new ExecutionSelection.Inputs(snapshotDirectory.toPath(), policyFile.toPath(), eligibilityFile.toPath(),
                    historyFile == null ? null : historyFile.toPath(), explicitManifestFile == null ? null : explicitManifestFile.toPath(),
                    profile, revision, selectedSuite, shardCount, compiledInputs(revision, selectedSuite));
            String digest = ExecutionSelection.write(inputs, selectionFile.toPath());
            getLog().info("ShardPilot execution selection SHA-256: " + digest);
        } catch (IllegalArgumentException failure) {
            throw new MojoFailureException("Invalid ShardPilot execution selection or retained provenance");
        } catch (IOException failure) {
            throw new MojoExecutionException("Cannot write ShardPilot execution selection");
        }
    }
}
