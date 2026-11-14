package io.github.sjacek.shardpilot;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;

/** Shared managed configuration; untracked state prevents Gradle from traversing untrusted trees. */
@DisableCachingByDefault(because = "C3 must inspect artifact bytes on every invocation")
public abstract class CompiledArtifactsTask extends DefaultTask {
    private CompiledArtifactOptions options;
    /** Creates an always-running artifact-only task with no compilation or dependency-resolution edges. */
    public CompiledArtifactsTask() {
        // This is stronger than merely disabling build-cache storage: even a caller's cacheIf
        // cannot restore an old verification result or let Gradle pre-walk untrusted file trees.
        doNotTrackState("C3 must inspect artifact bytes on every invocation, before filesystem snapshots");
        options = getProject().getObjects().newInstance(CompiledArtifactOptions.class);
        options.getBaseDirectory().convention(getProject().getLayout().getProjectDirectory());
        options.getManifestFile().convention(getProject().getLayout().getBuildDirectory().file("shardpilot/compiled-artifacts.json"));
    }
    /**
     * Exposes settings interpreted by the shared core rather than Gradle input snapshots.
     * @return settings interpreted by the shared core rather than Gradle input snapshots
     */
    @Internal public CompiledArtifactOptions getOptions() { return options; }
    /**
     * Shares a managed per-Test configuration with the producer, verifier and final worker guard.
     * @param options managed configuration, without references to producer tasks
     */
    public void setOptions(@org.jspecify.annotations.NonNull CompiledArtifactOptions options) { this.options = options; }
}
