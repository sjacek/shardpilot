package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Rechecks retained C4 evidence and final Docker state, including from an independent after-script job. */
@DisableCachingByDefault(because = "A previous diagnostics result cannot establish current container state")
public abstract class VerifyDiagnostics extends DiagnosticsTask {
    /** Creates the independent verifier; deliberately has no prepare/test dependencies. */
    public VerifyDiagnostics() { }
    /** Writes current JSON/HTML and fails incomplete, timed-out, interrupted, failed or leaking executions. */
    @TaskAction public void verify() {
        try {
            if (!Diagnostics.finish(getOptions().settings()).passed()) throw new GradleException("C4 DIAGNOSTICS_FAILED; inspect report.json and report.html");
        } catch (IOException failure) { throw new GradleException("C4 REPORT_WRITE_FAILED"); }
    }
}
