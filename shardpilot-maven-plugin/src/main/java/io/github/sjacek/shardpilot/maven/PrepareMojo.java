package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ShardManifests;
import java.io.IOException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Prepares all partitions after explicitly requested test compilation, without binding test goals.
 * Uses native eligibility/wiring validation even though no workers run. Invalid configuration or
 * history fails before replacing an earlier plan; filesystem failures can still leave partial output.
 */
@Mojo(name = "prepare", threadSafe = true)
public final class PrepareMojo extends AbstractShardMojo {
    /** Creates the container-managed planning goal. */
    public PrepareMojo() { }
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipAggregator()) return;
        try {
            var selection = new RunnerConfiguration(this).scan();
            ShardManifests.prepare(planDirectory(), selection.eligible(), history(), shardCount);
            getLog().info("ShardPilot plan: " + selection.eligible().size() + " classes across " + shardCount + " shards");
        } catch (IllegalArgumentException exception) {
            throw new MojoFailureException(exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new MojoExecutionException("Cannot prepare ShardPilot manifests", exception);
        }
    }
}
