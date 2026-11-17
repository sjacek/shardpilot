package io.github.sjacek.shardpilot.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/** Exports settings for the external diagnostics runner; does not run tests or contact Docker. */
@Mojo(name = "prepare-diagnostics", threadSafe = true, requiresProject = false)
public final class PrepareDiagnosticsMojo extends AbstractDiagnosticsMojo {
    /** Creates the standalone exporter. */
    public PrepareDiagnosticsMojo() { }
    @Override public void execute() throws MojoExecutionException, MojoFailureException { executeDiagnostics(true); }
}
