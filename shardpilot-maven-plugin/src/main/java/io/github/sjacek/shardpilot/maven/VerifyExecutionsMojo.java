package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ExecutionEvidence;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Opt-in artifact-only goal for either Surefire or Failsafe evidence. Runner/project/suite identity
 * comes from the independent expected request, not mutable reactor state. No default phase,
 * dependency resolution or project requirement is declared; an empty workspace can aggregate.
 */
@Mojo(name = "verify-executions", threadSafe = true, requiresProject = false)
public final class VerifyExecutionsMojo extends AbstractMojo {
    /** Creates the Maven-managed offline execution gate. */
    public VerifyExecutionsMojo() { }
    /** Independently CI-authored expected request. */
    @Parameter(property = "shardpilot.executionExpectedRequest", required = true)
    private File expectedRequest;
    /** Independently retained full native baseline. */
    @Parameter(property = "shardpilot.executionEligibilityFile", required = true)
    private File eligibilityFile;
    /** Canonical full/explicit selection bytes. */
    @Parameter(property = "shardpilot.executionSelectionFile", required = true)
    private File selectionFile;
    /** Read-only extracted node-N directories. */
    @Parameter(property = "shardpilot.executionNodesDirectory", required = true)
    private File nodesDirectory;
    /** JSON outside every retained-input path. */
    @Parameter(property = "shardpilot.executionReportFile", defaultValue = "${project.build.directory}/shardpilot/execution-evidence.json", required = true)
    private File reportFile;
    /** Standalone HTML outside every retained-input path. */
    @Parameter(property = "shardpilot.executionHtmlReportFile", defaultValue = "${project.build.directory}/shardpilot/execution-evidence.html", required = true)
    private File htmlReportFile;
    /** Maximum bytes per JSON document; can only be lowered. */
    @Parameter(property = "shardpilot.executionJsonBytes", defaultValue = "1048576") private int jsonBytes = 1_048_576;
    /** Maximum bytes per YAML manifest. */
    @Parameter(property = "shardpilot.executionManifestBytes", defaultValue = "1048576") private int manifestBytes = 1_048_576;
    /** Maximum nodes in one cohort. */
    @Parameter(property = "shardpilot.executionMaxNodes", defaultValue = "1024") private int maxNodes = 1024;
    /** Maximum XML reports per node. */
    @Parameter(property = "shardpilot.executionMaxReports", defaultValue = "20000") private int maxReports = 20_000;
    /** Maximum bytes per XML. */
    @Parameter(property = "shardpilot.executionMaxReportBytes", defaultValue = "67108864") private int maxReportBytes = 67_108_864;
    /** Maximum total XML bytes per node. */
    @Parameter(property = "shardpilot.executionMaxTotalReportBytes", defaultValue = "268435456") private int maxTotalReportBytes = 268_435_456;
    /** Maximum directory/XML depth. */
    @Parameter(property = "shardpilot.executionMaxDepth", defaultValue = "64") private int maxDepth = 64;
    /** Maximum testcase rows per node. */
    @Parameter(property = "shardpilot.executionMaxTestCases", defaultValue = "2000000") private int maxTestCases = 2_000_000;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            var limits = new ExecutionEvidence.Limits(jsonBytes, manifestBytes, maxNodes, maxReports, maxReportBytes, maxTotalReportBytes, maxDepth, maxTestCases);
            var inputs = new ExecutionEvidence.Inputs(expectedRequest.toPath(), eligibilityFile.toPath(), selectionFile.toPath(), nodesDirectory.toPath(), limits);
            var result = ExecutionEvidence.writeReports(inputs, reportFile.toPath(), htmlReportFile.toPath());
            if (!result.passed()) throw new MojoFailureException("ShardPilot execution evidence FAILED; inspect the C2 JSON/HTML reports");
            getLog().info("ShardPilot execution evidence PASSED");
        } catch (IllegalArgumentException failure) {
            // Invalid configuration/evidence fails a gate; inability to retain its reports is an
            // execution error. Neither wrapper retains a cause that could quote a private path.
            throw new MojoFailureException("Invalid ShardPilot execution-evidence configuration");
        } catch (IOException failure) {
            throw new MojoExecutionException("Cannot write ShardPilot execution-evidence reports");
        }
    }
}
