package io.github.sjacek.shardpilot;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks downloaded artifacts without regenerating a plan or rerunning tests. Every invocation
 * rechecks evidence, including after configuration-cache restoration. Validation failures replace
 * any earlier success reports with failure JSON/HTML; an unwritable report still fails the Gradle task.
 * Core validation returns one snapshot for both formats. This adapter translates its failure into
 * a Gradle gate outcome and deliberately adds no producer dependency, even when evidence is missing.
 */
@DisableCachingByDefault(because = "Each invocation must inspect the downloaded CI artifact cohort")
public abstract class VerifyShardManifests extends DefaultTask {
    /** Creates an always-running verification gate. */
    public VerifyShardManifests() { getOutputs().upToDateWhen(task -> false); }
    /**
     * Supplies directory of immutable planning evidence.
     * @return the configured value
     */
    @Internal
    public abstract DirectoryProperty getPlannedDirectory();
    /**
     * Supplies directory of actual candidate evidence.
     * @return the configured value
     */
    @Internal
    public abstract DirectoryProperty getActualDirectory();
    /**
     * Supplies planning inputs; missing files are reported by the action, including a failure JSON.
     * @return the configured value
     */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getPlannedFiles() { return getPlannedDirectory().getAsFileTree(); }
    /**
     * Supplies execution inputs; filenames retain the CI index.
     * @return the configured value
     */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getActualFiles() { return getActualDirectory().getAsFileTree(); }
    /**
     * Supplies expected number of partitions.
     * @return the configured value
     */
    @Input
    public abstract Property<Integer> getShardCount();
    /**
     * Supplies whether to check downloaded actual selections.
     * @return the configured value
     */
    @Input
    public abstract Property<Boolean> getVerifyActual();
    /**
     * Supplies policy for empty individual partitions.
     * @return the configured value
     */
    @Input
    public abstract Property<Boolean> getRequireNonEmptyShards();
    /**
     * Supplies report retained on success and failure.
     * @return the configured value
     */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /**
     * Supplies the standalone HTML report with assignments and failure diagnostics.
     * @return the configured output, independent of the JSON report location
     */
    @OutputFile
    public abstract RegularFileProperty getHtmlReportFile();

    /**
     * Fails the Gradle gate for missing, duplicated, foreign, stale or mismatched class selections.
     * @throws IOException if the verification report cannot be written
     */
    @TaskAction
    public void verify() throws IOException {
        var result = ShardCoverage.writeReports(getPlannedDirectory().get().getAsFile().toPath(),
                getActualDirectory().get().getAsFile().toPath(), getShardCount().get(),
                getVerifyActual().get(), getRequireNonEmptyShards().get(),
                getReportFile().get().getAsFile().toPath(), getHtmlReportFile().get().getAsFile().toPath());
        Exception failure = result.failure();
        if (failure != null) throw new GradleException("ShardPilot coverage verification failed: " + failure.getMessage(), failure);
        getLogger().lifecycle("ShardPilot coverage verified: {}", result.json().trim());
    }
}
