package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Explicit producer step after consumer-requested compilation; does not schedule that compilation. */
@DisableCachingByDefault(because = "A C3 manifest must describe this invocation's actual files")
public abstract class SealCompiledArtifacts extends CompiledArtifactsTask {
    /** Creates the explicitly invoked producer task. */
    public SealCompiledArtifacts() { }
    /**
     * Seals the existing artifacts with the common C3 boundary checks.
     */
    @TaskAction public void seal() {
        try {
            var result = CompiledArtifacts.seal(getOptions().inputs(), getOptions().getManifestFile().get().getAsFile().toPath());
            getLogger().lifecycle("ShardPilot C3 sealed {} files, {} bytes; manifest SHA-256 {}", result.files(), result.bytes(), result.manifestSha256());
        } catch (IOException failure) { throw new GradleException("Cannot write ShardPilot C3 manifest"); }
    }
}
