package io.github.sjacek.shardpilot;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;
import org.jspecify.annotations.NonNull;

/** Always-current C4 operations, with managed configuration retained across Configuration Cache reuse. */
@DisableCachingByDefault(because = "Diagnostic evidence must be checked on every invocation")
public abstract class DiagnosticsTask extends DefaultTask {
    private DiagnosticsOptions options;
    /** Creates standalone defaults without linking any test or compilation producers. */
    public DiagnosticsTask() {
        // Configuration Cache may reuse settings, but up-to-date/build-cache verdicts must never
        // skip a fresh inspection of retained files or the explicitly configured live daemon.
        doNotTrackState("C4 checks live process evidence and explicitly configured Docker state");
        options = getProject().getObjects().newInstance(DiagnosticsOptions.class);
        options.getDirectory().convention(getProject().getLayout().getBuildDirectory().dir("shardpilot/diagnostics"));
        options.getConfigurationFile().convention(getProject().getLayout().getBuildDirectory().file("shardpilot/diagnostics-config.json"));
    }
    /** Expected settings retained by the caller.
     * @return trusted local configuration
     */
    @Internal public DiagnosticsOptions getOptions() { return options; }
    /** Shares the owning task's settings.
     * @param options trusted configuration
     */
    public void setOptions(@NonNull DiagnosticsOptions options) { this.options = options; }
}
