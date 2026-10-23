package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ShardCoverage;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Standalone artifact gate, distinct from Maven's verify lifecycle phase. No lifecycle phase or
 * dependency resolution can manufacture missing evidence. Native runner configuration and compiled
 * classes are unnecessary: only the supplied plan/actual cohort is inspected. Both reports are
 * attempted before raising a validation failure; write failures remain execution errors.
 */
@Mojo(name = "verify", threadSafe = true)
public final class VerifyMojo extends AbstractShardMojo {
    /** Creates the container-managed artifact-verification goal. */
    public VerifyMojo() { }
    /** Optional JSON path, independent of HTML and manifest directory overrides. */
    @Parameter(property = "shardpilot.reportFile")
    private File reportFile;

    /** Optional standalone HTML path. */
    @Parameter(property = "shardpilot.htmlReportFile")
    private File htmlReportFile;

    /** Plan-only inspection is opt-in; final CI should require actual selections. */
    @Parameter(property = "shardpilot.verifyActual", defaultValue = "true")
    private boolean verifyActual = true;

    /** Reject empty partitions by default; the baseline is always required and nonempty. */
    @Parameter(property = "shardpilot.requireNonEmptyShards", defaultValue = "true")
    private boolean requireNonEmptyShards = true;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipAggregator()) return;
        try {
            var result = ShardCoverage.writeReports(planDirectory(), selectionDirectory(), shardCount,
                    verifyActual, requireNonEmptyShards,
                    reportFile == null ? namespace().resolve("coverage.json") : reportFile.toPath(),
                    htmlReportFile == null ? namespace().resolve("coverage.html") : htmlReportFile.toPath());
            if (result.failure() != null) throw new MojoFailureException("ShardPilot coverage verification failed: " + result.failure().getMessage(), result.failure());
            getLog().info("ShardPilot coverage verified: " + result.json().trim());
        } catch (IllegalArgumentException exception) {
            throw new MojoFailureException(exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new MojoExecutionException("Cannot write ShardPilot reports", exception);
        }
    }
}
