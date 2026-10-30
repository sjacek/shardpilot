package io.github.sjacek.shardpilot;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static io.github.sjacek.shardpilot.DurationHistoryRefresh.Source;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.Reason;

/**
 * Deterministic aggregation over supplied evidence. Data failures write JSON and remove stale TSV.
 * Configuration validation precedes parsing and does not replace existing artifacts. Missing data
 * is handled in the action, rather than rejected as a Gradle InputFile validation error, so a
 * failed baseline/XML read can still produce the same diagnostic JSON contract as core failures.
 */
@CacheableTask
public abstract class RefreshShardDurations extends DefaultTask {
    /** Creates a Gradle-managed refresh task. */
    public RefreshShardDurations() { }

    /**
     * Supplied report roots, without producer task dependencies.
     * @return supplied report roots, without producer task dependencies
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getReports();

    /**
     * Explicit eligibility bytecode roots.
     * @return explicit eligibility bytecode roots
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Detached owning-task bytecode roots, without compilation dependencies.
     * @return detached owning-task bytecode roots, without compilation dependencies
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDefaultClassDirectories();

    /**
     * Explicitly configured baseline; contents are snapshotted separately.
     * @return explicitly configured baseline; contents are snapshotted separately
     */
    @Internal
    public abstract RegularFileProperty getBaselineFile();

    /**
     * Lazy verification convention, including when verification is configured later.
     * @return lazy verification convention, including when verification is configured later
     */
    @Internal
    public abstract RegularFileProperty getVerificationBaseline();

    /**
     * Optional versioned history location, used only to prevent destructive output aliases.
     * @return optional versioned history location, used only to prevent destructive output aliases
     */
    @Internal
    public abstract RegularFileProperty getDurationsFile();

    /**
     * Baseline inputs as plain files, permitting missing-input failure JSON without producer dependencies.
     * @return baseline inputs as plain files, permitting missing-input failure JSON without producer dependencies
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public Set<File> getBaselineInputs() {
        if (getBaselineFile().isPresent()) return Set.of(getBaselineFile().get().getAsFile());
        return getVerificationBaseline().isPresent() ? Set.of(getVerificationBaseline().get().getAsFile()) : Set.of();
    }

    /**
     * Explicit override presence, distinct from a verification convention.
     * @return explicit override presence, distinct from a verification convention
     */
    @Input
    public boolean getExplicitBaseline() { return getBaselineFile().isPresent(); }

    /**
     * Whether verification supplies a baseline convention.
     * @return whether verification supplies a baseline convention
     */
    @Input
    public boolean getHasVerificationBaseline() { return getVerificationBaseline().isPresent(); }

    /**
     * Previous history contents, snapshotted with the same provider as the test filter.
     * @return previous history contents, snapshotted with the same provider as the test filter
     */
    @Input
    public abstract Property<String> getPreviousHistory();

    /**
     * Partition count for before/after load reporting.
     * @return partition count for before/after load reporting
     */
    @Input
    public abstract Property<Integer> getShardCount();

    /**
     * Lower bound for each accepted family's seconds.
     * @return lower bound for each accepted family's seconds
     */
    @Input
    public abstract Property<Double> getMinimumSeconds();

    /**
     * Decimal digits, 0–6.
     * @return decimal digits, 0–6
     */
    @Input
    public abstract Property<Integer> getPrecision();

    /**
     * Opt-in to non-retried failures.
     * @return opt-in to non-retried failures
     */
    @Input
    public abstract Property<Boolean> getIncludeFailedSuites();

    /**
     * Duplicate suite-name policy.
     * @return duplicate suite-name policy
     */
    @Input
    public abstract Property<DurationHistoryRefresh.Duplicates> getDuplicates();

    /**
     * Minimum fraction of eligible families measured.
     * @return minimum fraction of eligible families measured
     */
    @Input
    public abstract Property<Double> getMinimumCoverage();

    /**
     * Eligibility source-selection policy.
     * @return eligibility source-selection policy
     */
    @Input
    public abstract Property<ShardHistoryOptions.Pruning> getPruning();

    /**
     * Candidate TSV location.
     * @return candidate TSV location
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Success/failure JSON evidence.
     * @return success/failure JSON evidence
     */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /**
     * Distinguishes invalid aliases in the cache key: a prior valid cached action must never
     * bypass validation for an output now pointing at versioned input or at the other output.
     * @return whether output paths conflict with protected inputs or one another
     */
    @Input
    public boolean getOutputConflict() {
        Path output = getOutputFile().get().getAsFile().toPath();
        Path report = getReportFile().get().getAsFile().toPath();
        if (same(output, report)) return true;
        var protectedFiles = new HashSet<>(getBaselineInputs());
        if (getDurationsFile().isPresent()) protectedFiles.add(getDurationsFile().get().getAsFile());
        return protectedFiles.stream().anyMatch(file -> same(output, file.toPath()) || same(report, file.toPath()));
    }

    /**
     * Resolves policy before reading data, writes both successful artifacts, or JSON only on data failure.
     * @throws IOException if output directories/files cannot be written or stale output cannot be removed
     */
    @TaskAction
    public void refresh() throws IOException {
        var options = new DurationHistoryRefresh.Options(getMinimumSeconds().get(), getPrecision().get(),
                getIncludeFailedSuites().get(), getDuplicates().get(), getMinimumCoverage().get(), getShardCount().get());
        if (getOutputConflict()) throw new GradleException("ShardPilot outputFile/reportFile must be distinct and cannot overwrite durationsFile or baselineFile");
        Source source = source(); // All contradictory configuration is rejected before data reads, without JSON.
        DurationHistoryRefresh.Eligibility eligibility;
        try {
            eligibility = eligibility(source);
        } catch (IOException | IllegalArgumentException failure) {
            write(DurationHistoryRefresh.failed(Reason.INVALID_BASELINE, "Missing, unreadable or invalid eligibility source", source, null, options));
            return;
        }
        Map<String, Double> previous;
        try { previous = DurationHistory.parse(getPreviousHistory().get()); }
        catch (IllegalArgumentException failure) {
            write(DurationHistoryRefresh.failed(Reason.INVALID_HISTORY, "Invalid previous timing history", source,
                    source == Source.DISABLED ? null : eligibility.families().size(), options));
            return;
        }
        try {
            var suites = JUnitXmlTimings.read(getReports().getFiles().stream().map(File::toPath).toList());
            write(DurationHistoryRefresh.refresh(previous, suites, eligibility, options));
        } catch (JUnitXmlTimings.ReadFailure failure) {
            var partial = DurationHistoryRefresh.refresh(previous, failure.partial(), eligibility, options);
            write(new DurationHistoryRefresh.Result(Map.of(), partial.report().failedRead(Reason.INVALID_XML, failure.getMessage())));
        }
    }

    private Source source() {
        // Presence selects a source; readability never triggers a silent fallback to another source.
        boolean baseline = getBaselineFile().isPresent();
        boolean classes = !getClassDirectories().isEmpty();
        boolean verification = getVerificationBaseline().isPresent();
        return switch (getPruning().get()) {
            case AUTO -> {
                if (baseline && classes) throw configuration("AUTO cannot combine explicit baselineFile and classDirectories");
                yield baseline ? Source.BASELINE : classes ? Source.CLASS_DIRECTORIES : verification ? Source.BASELINE : Source.CLASS_DIRECTORIES;
            }
            case BASELINE -> {
                if (classes || !baseline && !verification) throw configuration("BASELINE requires a baseline and forbids explicit classDirectories");
                yield Source.BASELINE;
            }
            case CLASS_DIRECTORIES -> {
                if (baseline) throw configuration("CLASS_DIRECTORIES forbids explicit baselineFile");
                yield Source.CLASS_DIRECTORIES;
            }
            case DISABLED -> {
                if (baseline || classes || getMinimumCoverage().get() > 0) throw configuration("DISABLED forbids explicit sources and minimumCoverage > 0");
                yield Source.DISABLED;
            }
        };
    }

    private DurationHistoryRefresh.Eligibility eligibility(Source source) throws IOException {
        if (source == Source.DISABLED) return DurationHistoryRefresh.Eligibility.disabled();
        if (source == Source.BASELINE) {
            var file = getBaselineFile().isPresent() ? getBaselineFile().get() : getVerificationBaseline().get();
            return new DurationHistoryRefresh.Eligibility(source, ClassManifest.read(file.getAsFile().toPath(), true));
        }
        var roots = getClassDirectories().isEmpty() ? getDefaultClassDirectories() : getClassDirectories();
        var files = new HashSet<File>();
        for (File root : roots) {
            if (!root.isDirectory()) throw new IllegalArgumentException("Missing class directory");
            try (var walk = Files.walk(root.toPath())) {
                walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).map(Path::toFile).forEach(files::add);
            }
        }
        // Derive names without loading bytecode and use exactly the existing manifest naming rules.
        return new DurationHistoryRefresh.Eligibility(source, ClassManifest.compiled(files, roots.getFiles()));
    }

    private void write(DurationHistoryRefresh.Result result) throws IOException {
        Path output = getOutputFile().get().getAsFile().toPath();
        Path report = getReportFile().get().getAsFile().toPath();
        // A failed data refresh must not leave the previous successful TSV available for artifact upload.
        // Writes are not a multi-file transaction: output I/O errors still fail the Gradle task.
        if (!result.passed()) Files.deleteIfExists(output);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, result.report().json(), java.nio.charset.StandardCharsets.UTF_8);
        getLogger().lifecycle(result.report().lifecycle());
        if (!result.passed()) throw new GradleException("ShardPilot history refresh failed: " + result.report().reason());
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, DurationHistory.format(result.history(), getPrecision().get()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static GradleException configuration(String message) { return new GradleException("ShardPilot history configuration: " + message); }

    private static boolean same(Path left, Path right) {
        try { return identity(left).equals(identity(right)) || Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right); }
        catch (IOException failure) { return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()); }
    }

    private static Path identity(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        Path parent = absolute.getParent();
        return parent == null ? absolute : identity(parent).resolve(absolute.getFileName());
    }
}
