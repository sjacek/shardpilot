package io.github.sjacek.shardpilot;

import java.io.Serial;
import java.io.Serializable;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.jspecify.annotations.NonNull;

/**
 * Recheck immediately before the worker: other explicitly scheduled tasks may have changed files
 * after the dependency gate. Retain only managed options, never a Project or Task reference, so
 * Configuration Cache reuse rehydrates the same configuration without preserving a previous result.
 */
final class CompiledArtifactGuard implements Action<Task>, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final CompiledArtifactOptions options;
    @SuppressWarnings("unused") // Reached through the public Gradle DSL; build-script callers are outside Java analysis.
    CompiledArtifactGuard(CompiledArtifactOptions options) { this.options = options; }
    @Override public void execute(@NonNull Task task) { options.verify(); }
}
