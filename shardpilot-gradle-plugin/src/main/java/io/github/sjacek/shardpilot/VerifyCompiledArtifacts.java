package io.github.sjacek.shardpilot;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Always-running C3 gate, also available without a JVM plugin or any native Test task. */
@DisableCachingByDefault(because = "C3 may not reuse an earlier verification result")
public abstract class VerifyCompiledArtifacts extends CompiledArtifactsTask {
    /** Creates the artifact-only verifier. */
    public VerifyCompiledArtifacts() { }
    /** Rechecks the complete inventory before allowing dependent test tasks to snapshot their inputs. */
    @TaskAction public void verify() {
        var result = getOptions().verify();
        getLogger().lifecycle("ShardPilot C3 verified {} files, {} bytes", result.files(), result.bytes());
    }
}
