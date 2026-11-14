package io.github.sjacek.shardpilot.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/** Bind explicitly before Surefire/Failsafe, or invoke before their direct goals on precompiled workers. */
@Mojo(name = "verify-compiled-artifacts", threadSafe = true, requiresProject = false)
public final class VerifyCompiledArtifactsMojo extends AbstractCompiledArtifactsMojo {
    /** Creates the offline pre-test gate. */
    public VerifyCompiledArtifactsMojo() { }
    @Override public void execute() throws MojoExecutionException, MojoFailureException { run(false); }
}
