package io.github.sjacek.shardpilot.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/** Explicitly seals existing outputs after consumer-requested compilation, without forking a lifecycle. */
@Mojo(name = "seal-compiled-artifacts", threadSafe = true, requiresProject = false)
public final class SealCompiledArtifactsMojo extends AbstractCompiledArtifactsMojo {
    /** Creates the explicit producer goal. */
    public SealCompiledArtifactsMojo() { }
    @Override public void execute() throws MojoExecutionException, MojoFailureException { run(true); }
}
