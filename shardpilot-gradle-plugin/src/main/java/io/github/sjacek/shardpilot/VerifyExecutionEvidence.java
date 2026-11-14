package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Artifact-only C2 gate. No producer dependencies, native test execution or dependency resolution. */
@DisableCachingByDefault(because = "C2 must inspect every retained cohort on every invocation")
public abstract class VerifyExecutionEvidence extends DefaultTask {
    /** Creates an always-running verifier with the distribution's hard limits. */
    public VerifyExecutionEvidence() {
        // Disable all state snapshots, not only cache hits: Gradle must not traverse untrusted
        // inputs or aliased output paths before core has applied the C2 boundary checks.
        doNotTrackState("C2 validates retained artifact bytes on every invocation");
        var defaults = ExecutionEvidence.Limits.defaults();
        getJsonBytes().convention(defaults.jsonBytes()); getManifestBytes().convention(defaults.manifestBytes());
        getMaxNodes().convention(defaults.maxNodes()); getMaxReports().convention(defaults.maxReports());
        getMaxReportBytes().convention(defaults.maxReportBytes()); getMaxTotalReportBytes().convention(defaults.maxTotalReportBytes());
        getMaxDepth().convention(defaults.maxDepth()); getMaxTestCases().convention(defaults.maxTestCases());
    }

    /** Supplies separately authored trusted aggregation request.
     * @return separately authored trusted aggregation request
     */
    @Internal public abstract RegularFileProperty getExpectedRequest();
    /** Supplies independently retained full eligibility.
     * @return independently retained full eligibility
     */
    @Internal public abstract RegularFileProperty getEligibilityFile();
    /** Supplies independently retained canonical full/explicit selection.
     * @return independently retained canonical full/explicit selection
     */
    @Internal public abstract RegularFileProperty getSelectionFile();
    /** Supplies read-only extracted node directories.
     * @return read-only extracted node directories
     */
    @Internal public abstract DirectoryProperty getNodesDirectory();
    /**
     * Declares the input locations without Gradle walking/hashing untrusted trees first. Core
     * enforces path/size/depth limits before reading bytes on every invocation; this always-running,
     * noncacheable task must not let an input snapshot follow a producer's symlink before validation.
     * @return declared trusted metadata locations and downloaded-artifact root
     */
    @Input
    public java.util.List<String> getInputLocations() {
        return java.util.List.of(getExpectedRequest().get().getAsFile().getAbsolutePath(), getEligibilityFile().get().getAsFile().getAbsolutePath(),
                getSelectionFile().get().getAsFile().getAbsolutePath(), getNodesDirectory().get().getAsFile().getAbsolutePath());
    }
    /** Supplies separate JSON result.
     * @return separate JSON result
     */
    @OutputFile public abstract RegularFileProperty getReportFile();
    /** Supplies separate standalone HTML result.
     * @return separate standalone HTML result
     */
    @OutputFile public abstract RegularFileProperty getHtmlReportFile();
    /** Supplies maximum bytes per JSON document.
     * @return maximum bytes per JSON document
     */
    @Input public abstract Property<Integer> getJsonBytes();
    /** Supplies maximum bytes per YAML manifest.
     * @return maximum bytes per YAML manifest
     */
    @Input public abstract Property<Integer> getManifestBytes();
    /** Supplies maximum number of nodes.
     * @return maximum number of nodes
     */
    @Input public abstract Property<Integer> getMaxNodes();
    /** Supplies maximum XML files per node.
     * @return maximum XML files per node
     */
    @Input public abstract Property<Integer> getMaxReports();
    /** Supplies maximum bytes per XML.
     * @return maximum bytes per XML
     */
    @Input public abstract Property<Integer> getMaxReportBytes();
    /** Supplies maximum total XML bytes per node.
     * @return maximum total XML bytes per node
     */
    @Input public abstract Property<Integer> getMaxTotalReportBytes();
    /** Supplies maximum directory/XML nesting depth.
     * @return maximum directory/XML nesting depth
     */
    @Input public abstract Property<Integer> getMaxDepth();
    /** Supplies maximum testcase rows per node.
     * @return maximum testcase rows per node
     */
    @Input public abstract Property<Integer> getMaxTestCases();

    /** Writes both reports before translating a failed validation into a failed Gradle task.
     * @throws IOException if reports cannot be written
     */
    @TaskAction
    public void verify() throws IOException {
        var limits = new ExecutionEvidence.Limits(getJsonBytes().get(), getManifestBytes().get(), getMaxNodes().get(),
                getMaxReports().get(), getMaxReportBytes().get(), getMaxTotalReportBytes().get(), getMaxDepth().get(), getMaxTestCases().get());
        var inputs = new ExecutionEvidence.Inputs(getExpectedRequest().get().getAsFile().toPath(), getEligibilityFile().get().getAsFile().toPath(),
                getSelectionFile().get().getAsFile().toPath(), getNodesDirectory().get().getAsFile().toPath(), limits);
        var result = ExecutionEvidence.writeReports(inputs, getReportFile().get().getAsFile().toPath(), getHtmlReportFile().get().getAsFile().toPath());
        if (!result.passed()) throw new GradleException("ShardPilot execution evidence FAILED; inspect the C2 JSON/HTML reports");
        getLogger().lifecycle("ShardPilot execution evidence PASSED");
    }
}
