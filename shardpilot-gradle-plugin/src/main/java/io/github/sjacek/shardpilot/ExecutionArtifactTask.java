package io.github.sjacek.shardpilot;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.work.DisableCachingByDefault;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Optional C3 inputs for offline execution-selection preparation and C2 aggregation. */
@DisableCachingByDefault(because = "Execution evidence must inspect retained input bytes on every invocation")
@NullMarked
public abstract class ExecutionArtifactTask extends DefaultTask {
    private @Nullable CompiledArtifactOptions compiled;

    /** Creates configuration only; no files, dependency configurations or producers are resolved. */
    public ExecutionArtifactTask() { }

    /**
     * Enables independent C3 verification. Roots, revision and suite remain trusted configuration;
     * no value is adopted from a downloaded manifest and no sealing task is scheduled.
     * @param action retained manifest, actual input trees, expected identity and optional lower limits
     */
    @SuppressWarnings("unused") // Public managed Groovy/Kotlin DSL, exercised through TestKit builds.
    public void compiledArtifacts(Action<? super CompiledArtifactOptions> action) {
        if (compiled != null) throw new IllegalStateException("Compiled execution inputs are already configured");
        var options = getProject().getObjects().newInstance(CompiledArtifactOptions.class);
        options.getBaseDirectory().convention(getProject().getLayout().getProjectDirectory());
        compiledConventions(options);
        action.execute(options);
        compiled = options;
    }

    /**
     * Describes configured locations/requirements without reading any artifact bytes.
     * This is diagnostic task metadata, not a replacement for integrity checks. Concrete execution
     * gates are untracked and validate the files again even when consumers enable broad build caching.
     * @return metadata-only C3 input locations
     */
    @Input
    public List<String> getCompiledInputLocations() {
        var options = compiled;
        if (options == null) return List.of();
        var values = new ArrayList<>(List.of(options.getBaseDirectory().get().getAsFile().getAbsolutePath(),
                options.getManifestFile().get().getAsFile().getAbsolutePath(), options.getRevision().get(), options.getSuite().get()));
        values.addAll(options.getRoots().get()); values.addAll(options.getRequiredDirectories().get()); values.addAll(options.getRequiredFiles().get());
        return values;
    }

    /** Producer tasks can default C3 identity from their separately configured expected identity.
     * @param options managed configuration before the consumer action
     */
    protected void compiledConventions(CompiledArtifactOptions options) {
        // Aggregation deliberately has no inferred identity: configure it independently of producer data.
    }

    /** Materializes trusted configuration at execution time without discovering roots from a manifest. */
    ExecutionSelection.@Nullable Compiled compiledInputs() {
        var options = compiled;
        return options == null ? null : new ExecutionSelection.Compiled(options.inputs(),
                options.getManifestFile().get().getAsFile().toPath(), options.getExpectedManifestSha256().get());
    }
}
