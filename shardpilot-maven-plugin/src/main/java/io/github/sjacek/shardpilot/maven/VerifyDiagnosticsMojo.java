package io.github.sjacek.shardpilot.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/** Independent after-script diagnostics check; no test producers or container mutations. */
@Mojo(name = "verify-diagnostics", threadSafe = true, requiresProject = false)
public final class VerifyDiagnosticsMojo extends AbstractDiagnosticsMojo {
    /** Creates the standalone verifier. */
    public VerifyDiagnosticsMojo() { }
    @Override public void execute() throws MojoExecutionException, MojoFailureException { executeDiagnostics(false); }
}
