package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.Diagnostics;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/** Optional C4 entry points share settings with Gradle; no native test lifecycle is scheduled. */
public abstract class AbstractDiagnosticsMojo extends AbstractMojo {
    /** Creates Maven-injected configuration. */
    protected AbstractDiagnosticsMojo() { }
    /** Private configuration file outside the session directory. */
    @Parameter(property = "shardpilot.diagnosticsConfigurationFile", defaultValue = "${project.build.directory}/shardpilot/diagnostics-config.json", required = true)
    protected File diagnosticsConfigurationFile;
    /** Fresh session directory, unique to one job and attempt. */
    @Parameter(property = "shardpilot.diagnosticsDirectory", defaultValue = "${project.build.directory}/shardpilot/diagnostics", required = true)
    protected File diagnosticsDirectory;
    /** Independently supplied full lowercase revision. */
    @Parameter(property = "shardpilot.diagnosticsRevision", required = true) protected String diagnosticsRevision;
    /** CI run identity. */
    @Parameter(property = "shardpilot.diagnosticsRunId", required = true) protected String diagnosticsRunId;
    /** Positive CI attempt number. */
    @Parameter(property = "shardpilot.diagnosticsAttempt", required = true) protected int diagnosticsAttempt;
    /** Suite identity. */
    @Parameter(property = "shardpilot.diagnosticsSuite", required = true) protected String diagnosticsSuite;
    /** Parallel job/shard identity. */
    @Parameter(property = "shardpilot.diagnosticsNode", required = true) protected String diagnosticsNode;
    /** Native timeout, zero disables the deadline. */
    @Parameter(property = "shardpilot.diagnosticsTimeoutMillis", defaultValue = "0") protected long diagnosticsTimeoutMillis;
    /** Sampling interval. */
    @Parameter(property = "shardpilot.diagnosticsSampleIntervalMillis", defaultValue = "1000") protected long diagnosticsSampleIntervalMillis = 1000;
    /** Retained-sample ceiling, 1–10000; reaching it does not disable the native deadline. */
    @Parameter(property = "shardpilot.diagnosticsMaxSamples", defaultValue = "10000") protected int diagnosticsMaxSamples = 10_000;
    /** Optional explicit job cgroup. */
    @Parameter(property = "shardpilot.diagnosticsJobCgroup") protected File diagnosticsJobCgroup;
    /** Optional explicit DinD cgroup, separately reported from the job. */
    @Parameter(property = "shardpilot.diagnosticsDindCgroup") protected File diagnosticsDindCgroup;
    /** Whether visible host counters are sampled, independently from job/DinD limits. */
    @Parameter(property = "shardpilot.diagnosticsHostMemory", defaultValue = "false") protected boolean diagnosticsHostMemory;
    /** Docker executable/prefix; empty disables checks. */
    @Parameter(property = "shardpilot.diagnosticsDockerCommand") protected List<String> diagnosticsDockerCommand;
    /** Explicit endpoint, never inferred from the environment. */
    @Parameter(property = "shardpilot.diagnosticsDockerHost") protected String diagnosticsDockerHost;
    /** Consumer assertion of exclusive Docker daemon ownership. */
    @Parameter(property = "shardpilot.diagnosticsDedicatedDocker", defaultValue = "false") protected boolean diagnosticsDedicatedDocker;

    /**
     * Delegates settings generation or a fresh evidence/container check to the shared core.
     * @param prepare whether to export configuration only
     * @throws MojoFailureException for invalid settings or failed diagnostics
     * @throws MojoExecutionException if retained output cannot be written
     */
    protected final void executeDiagnostics(boolean prepare) throws MojoFailureException, MojoExecutionException {
        try {
            // Reconstruct expectations from caller-owned Maven configuration, not from a downloaded
            // producer result. Verification deliberately has no prepare/test lifecycle dependency.
            var settings = new Diagnostics.Settings(new Diagnostics.Identity(diagnosticsRevision, diagnosticsRunId, diagnosticsAttempt, diagnosticsSuite, diagnosticsNode),
                    diagnosticsDirectory.toPath(), diagnosticsTimeoutMillis, diagnosticsSampleIntervalMillis, diagnosticsMaxSamples,
                    diagnosticsJobCgroup == null ? null : diagnosticsJobCgroup.toPath(), diagnosticsDindCgroup == null ? null : diagnosticsDindCgroup.toPath(),
                    diagnosticsHostMemory, diagnosticsDockerCommand == null ? List.of() : diagnosticsDockerCommand,
                    diagnosticsDockerHost == null ? "" : diagnosticsDockerHost, diagnosticsDedicatedDocker);
            if (prepare) Diagnostics.prepare(settings, diagnosticsConfigurationFile.toPath());
            else if (!Diagnostics.finish(settings).passed()) throw new MojoFailureException("C4 DIAGNOSTICS_FAILED; inspect report.json and report.html");
        } catch (Diagnostics.Problem invalid) { throw new MojoFailureException(invalid.getMessage()); }
        // Native I/O exceptions can expose paths. Publish a fixed code without the original cause.
        catch (IOException failure) { throw new MojoExecutionException("C4 DIAGNOSTICS_OUTPUT_UNAVAILABLE"); }
    }
}
