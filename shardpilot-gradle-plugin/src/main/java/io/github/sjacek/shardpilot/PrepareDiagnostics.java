package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Exports private settings for the external JDK-only supervisor without executing tests. */
@DisableCachingByDefault(because = "Diagnostic configuration must reflect this CI attempt")
public abstract class PrepareDiagnostics extends DiagnosticsTask {
    /** Creates the opt-in configuration exporter. */
    public PrepareDiagnostics() { }
    /** Writes settings without contacting Docker, resolving dependencies or starting a session. */
    @TaskAction public void prepare() {
        try { Diagnostics.prepare(getOptions().settings(), getOptions().getConfigurationFile().get().getAsFile().toPath()); }
        catch (IOException failure) { throw new GradleException("C4 CONFIGURATION_WRITE_FAILED"); }
    }
}
