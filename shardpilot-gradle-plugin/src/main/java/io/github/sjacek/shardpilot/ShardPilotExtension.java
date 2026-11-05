package io.github.sjacek.shardpilot;

import org.gradle.api.Action;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import java.io.File;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.testing.Test;

import javax.inject.Inject;

/** Per-Test-task settings. Applying the plugin never shards a task without explicit opt-in. */
public abstract class ShardPilotExtension {
    private final Test task;
    private final ObjectFactory objects;
    private boolean verificationConfigured;
    private boolean historyConfigured;
    private boolean selectionConfigured;
    private final ShardSelectionOptions selection;
    private final RegularFileProperty selectedManifest;
    private final RegularFileProperty verificationBaseline;
    private final Property<Integer> verificationShardCount;
    private final Property<Boolean> selectionEnabled;

    /**
     * Creates per-task settings managed and instantiated by Gradle.
     * @param task the owning test task
     * @param objects Gradle's object factory
     */
    @Inject
    public ShardPilotExtension(Test task, ObjectFactory objects) {
        this.task = task;
        this.objects = objects;
        this.verificationBaseline = objects.fileProperty();
        this.verificationShardCount = objects.property(Integer.class);
        this.selection = objects.newInstance(ShardSelectionOptions.class);
        this.selection.getClassDirectories().from(task.getTestClassesDirs());
        this.selectedManifest = objects.fileProperty();
        this.selectionEnabled = objects.property(Boolean.class).convention(false);
    }

    /**
     * Enables a consumer-supplied subset, including for a single shard. Configure once outside
     * deferred task configuration, like verification(). Eligibility must not use the already
     * filtered candidate tree. Explicit selection requires enabled=true on the owning task.
     * @param action manifest, separate suite identity and full eligible bytecode
     */
    public void selection(Action<? super ShardSelectionOptions> action) {
        if (selectionConfigured) throw new IllegalStateException("ShardPilot selection is already configured for " + task.getName());
        selectionConfigured = true;
        selectionEnabled.set(true);
        action.execute(selection);
        var project = task.getProject();
        String taskName = task.getName();
        String suffix = Character.toUpperCase(taskName.charAt(0)) + taskName.substring(1);
        var prepare = project.getTasks().register("prepare" + suffix + "Selection", PrepareTestSelection.class, producer -> {
            producer.setGroup("verification");
            producer.setDescription("Validates the explicit selection and records both eligibility baselines.");
            producer.getManifestFile().set(selection.getManifestFile());
            producer.getSuite().set(selection.getSuite());
            producer.getClasses().from(selection.getEligibleClasses());
            producer.getClassDirectories().from(selection.getClassDirectories());
            producer.getShardCount().set(getShardCount());
            producer.getHistory().set(project.getProviders().fileContents(getDurationsFile()).getAsText().orElse(""));
            producer.getDirectory().set(project.getLayout().getBuildDirectory().dir(
                    selection.getSuite().map(suite -> {
                        TestSelection.validateSuite(suite);
                        return "shardpilot/" + taskName + "/selections/" + suite + "/selection";
                    })));
        });
        selectedManifest.set(prepare.flatMap(producer -> producer.getDirectory().file("selected.yaml")));
        // This dependency runs before Test's skip/NO-SOURCE checks, unlike a doFirst validation.
        var validate = project.getTasks().register("validate" + suffix + "Selection", ValidateTestSelection.class, guard -> {
            guard.setGroup("verification");
            guard.setDescription("Checks explicit candidate equality before native Test can skip execution.");
            guard.getClasses().from(task.getCandidateClassFiles());
            guard.getClassDirectories().from(task.getTestClassesDirs());
            guard.getSelectionManifest().set(selectedManifest);
            guard.getShardCount().set(getShardCount());
            guard.getShardIndex().set(getShardIndex());
            guard.getHistory().set(project.getProviders().fileContents(getDurationsFile()).getAsText().orElse(""));
        });
        task.dependsOn(validate);
        task.getReports().getJunitXml().getOutputLocation().set(project.getLayout().getBuildDirectory()
                .dir(outputNamespace().map(path -> path + "/test-results")));
        task.getReports().getHtml().getOutputLocation().set(project.getLayout().getBuildDirectory()
                .dir(outputNamespace().map(path -> path + "/test-report")));
    }

    // Internal adapter wiring. Providers keep configuration order independent and cache-safe.
    RegularFileProperty selectedManifest() { return selectedManifest; }

    Provider<Boolean> selectionEnabled() { return selectionEnabled; }

    Provider<RegularFile> selectionIdentity() {
        return task.getProject().getLayout().file(selectedManifest.map(file ->
                file.getAsFile().toPath().resolveSibling("selection.id").toFile()));
    }

    Provider<Directory> selectedDirectory(
            Provider<Directory> base) {
        return task.getProject().getLayout().dir(selectedOutput(base.map(Directory::getAsFile)));
    }

    Provider<RegularFile> selectedFile(
            Provider<RegularFile> base) {
        return task.getProject().getLayout().file(selectedOutput(base.map(RegularFile::getAsFile)));
    }

    private Provider<File> selectedOutput(Provider<File> base) {
        // Rebase explicit overrides too, retaining their filename without reusing full-suite outputs.
        return selection.getSuite().flatMap(suite -> base.map(file -> {
            TestSelection.validateSuite(suite);
            return file.toPath().toAbsolutePath().getParent().resolve("selections").resolve(suite)
                    .resolve(file.getName()).toFile();
        })).orElse(base);
    }

    Provider<String> outputNamespace() {
        String base = "shardpilot/" + task.getName();
        return selection.getSuite().map(suite -> {
            TestSelection.validateSuite(suite);
            return base + "/selections/" + suite;
        }).orElse(base);
    }

    /**
     * Explicitly configures Gradle's test processes, even when sharding is disabled.
     * Only supplied values are changed. Providers are resolved after the action returns,
     * during task configuration, before Gradle snapshots inputs or launches a process.
     * Later native Test configuration can override these values in the usual Gradle order.
     * @param action optional heap, parallel process count and process replacement settings
     */
    public void testProcesses(Action<? super TestProcessOptions> action) {
        TestProcessOptions options = objects.newInstance(TestProcessOptions.class);
        action.execute(options);
        options.applyTo(task);
    }

    /**
     * Registers opt-in manifest preparation, actual selection and cross-job coverage verification.
     * The caller supplies an unsharded eligible class tree, including its own discovery restrictions.
     * No application naming convention or CI provider is assumed. Configure this block once per task.
     * Call outside a deferred TaskProvider.configure action, which cannot register sibling tasks.
     * Only the actual-manifest task becomes a test dependency. Preparation and aggregation remain
     * explicit CI steps; an aggregation-only invocation never manufactures missing evidence.
     * @param action eligible bytecode, partition size, artifact directories and verification policy
     */
    public void verification(Action<? super ShardVerificationOptions> action) {
        if (verificationConfigured) throw new IllegalStateException("ShardPilot verification is already configured for " + task.getName());
        verificationConfigured = true;
        ShardVerificationOptions options = objects.newInstance(ShardVerificationOptions.class);
        String directory = "shardpilot/" + task.getName();
        options.getClassDirectories().from(task.getTestClassesDirs());
        options.getShardCount().convention(getShardCount());
        options.getPlannedDirectory().convention(task.getProject().getLayout().getBuildDirectory().dir(directory + "/plan"));
        options.getActualDirectory().convention(task.getProject().getLayout().getBuildDirectory().dir(directory + "/actual"));
        options.getReportFile().convention(task.getProject().getLayout().getBuildDirectory().file(directory + "/coverage.json"));
        options.getHtmlReportFile().convention(task.getProject().getLayout().getBuildDirectory().file(directory + "/coverage.html"));
        options.getVerifyActual().convention(task.getProject().getProviders().gradleProperty("shardpilot.verifyActual")
                .map(Boolean::parseBoolean).orElse(true));
        options.getRequireNonEmptyShards().convention(true);
        action.execute(options);
        verificationBaseline.set(selectedDirectory(options.getPlannedDirectory()).map(dir -> dir.file("baseline.yaml")));
        verificationShardCount.set(options.getShardCount());
        ShardVerification.configure(task, this, options);
    }

    /**
     * Registers an explicit, cacheable refresh task using already available reports and bytecode.
     * Configure once, outside a deferred TaskProvider.configure action, like verification().
     * No test or compilation dependency is added; callers download or prepare evidence explicitly.
     * Verification conventions are resolved lazily, irrespective of the order of these two blocks.
     * @param action reports, eligibility source, output locations and conservative refresh policies
     */
    public void history(Action<? super ShardHistoryOptions> action) {
        if (historyConfigured) throw new IllegalStateException("ShardPilot history is already configured for " + task.getName());
        historyConfigured = true;
        ShardHistoryOptions options = objects.newInstance(ShardHistoryOptions.class);
        var layout = task.getProject().getLayout();
        var directory = outputNamespace();
        options.getReports().from(task.getReports().getJunitXml().getOutputLocation());
        options.getOutputFile().convention(layout.getBuildDirectory().file(directory.map(path -> path + "/durations.tsv")));
        options.getReportFile().convention(layout.getBuildDirectory().file(directory.map(path -> path + "/durations.json")));
        options.getMinimumSeconds().convention(.1);
        options.getPrecision().convention(1);
        options.getIncludeFailedSuites().convention(false);
        options.getDuplicates().convention(DurationHistoryRefresh.Duplicates.FAIL);
        options.getMinimumCoverage().convention(0.0);
        options.getPruning().convention(ShardHistoryOptions.Pruning.AUTO);
        action.execute(options);
        ShardHistory.configure(task, this, options, verificationBaseline, verificationShardCount);
    }

    /**
     * Controls opt-in for this task.
     * @return whether this test task participates in sharding; defaults to false
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Configures the size of the CI partition.
     * @return total shards, defaulting to the shardpilot.count Gradle property, then 1
     */
    public abstract Property<Integer> getShardCount();

    /**
     * Selects this job's partition.
     * @return one-based shard index, defaulting to the shardpilot.index Gradle property, then 1
     */
    public abstract Property<Integer> getShardIndex();

    /**
     * Supplies immutable timing metadata shared by all jobs in a run.
     * @return optional UTF-8 TSV history; unset or missing history uses hash-only assignment
     */
    public abstract RegularFileProperty getDurationsFile();
}
