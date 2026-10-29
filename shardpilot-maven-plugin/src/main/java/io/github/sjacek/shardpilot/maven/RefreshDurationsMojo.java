package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.DurationHistoryRefresh;
import io.github.sjacek.shardpilot.JUnitXmlTimings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import static io.github.sjacek.shardpilot.DurationHistoryRefresh.Source;
import static io.github.sjacek.shardpilot.HistoryRefreshReport.Reason;

/**
 * Refreshes a candidate history from existing XML and eligibility artifacts. This standalone,
 * thread-safe goal has no lifecycle binding, fork or dependency resolution: invoking it cannot
 * run tests, compile classes or regenerate a missing baseline. Configuration errors preserve
 * existing outputs; data errors publish FAILED JSON and remove a stale candidate TSV. Maintained
 * history is always read-only, including when an output aliases it through a link.
 * Each reactor project owns its namespace and goal instance. No selection, native-runner
 * configuration or session property is changed while collecting timing evidence.
 */
@Mojo(name = "refresh-durations", threadSafe = true)
public final class RefreshDurationsMojo extends AbstractShardMojo {
    /** Creates the container-managed history-refresh goal. */
    public RefreshDurationsMojo() { }

    /**
     * XML roots/files; defaults to target/surefire-reports or target/failsafe-reports.
     * Multiple roots may collect independent shards. The core deduplicates overlapping file
     * paths, but equal suite names in distinct files still follow the duplicates policy.
     */
    @Parameter(property = "shardpilot.reports")
    private File[] reports;

    /** Candidate TSV, defaulting to target/shardpilot/SUITE/durations.tsv. */
    @Parameter(property = "shardpilot.outputFile")
    private File outputFile;

    /** Success/failure evidence, defaulting to target/shardpilot/SUITE/durations.json. */
    @Parameter(property = "shardpilot.reportFile")
    private File reportFile;

    /**
     * Explicit eligibility manifest; otherwise use plan/baseline.yaml in the suite namespace.
     * An empty manifest is a configured empty eligible set, while a missing/invalid manifest
     * is a data error. Neither case silently disables pruning or scans compiled classes.
     */
    @Parameter(property = "shardpilot.baselineFile")
    private File baselineFile;

    /**
     * Optional bytecode roots, used only when selected by the pruning policy. Binary names
     * are derived without class loading. These roots include all supplied compiled classes;
     * use the prepared baseline to retain the native runner's include/exclude eligibility.
     */
    @Parameter(property = "shardpilot.classDirectories")
    private File[] classDirectories;

    /** Source presence selects eligibility; an unreadable baseline never triggers a fallback. */
    @Parameter(property = "shardpilot.pruning", defaultValue = "AUTO")
    private HistoryPruning pruning = HistoryPruning.AUTO;

    /** Positive family-time floor, applied once after normalization. */
    @Parameter(property = "shardpilot.minimumSeconds", defaultValue = "0.1")
    private double minimumSeconds = .1;

    /** Fractional digits in the candidate TSV, from zero to six. */
    @Parameter(property = "shardpilot.precision", defaultValue = "1")
    private int precision = 1;

    /** Permit non-retried failures; retry and ambiguous measurements remain excluded. */
    @Parameter(property = "shardpilot.includeFailedSuites", defaultValue = "false")
    private boolean includeFailedSuites;

    /** Repeated raw suite names fail unless the consumer explicitly opts into SUM. */
    @Parameter(property = "shardpilot.duplicates", defaultValue = "FAIL")
    private DurationHistoryRefresh.Duplicates duplicates = DurationHistoryRefresh.Duplicates.FAIL;

    /** Required fraction of eligible families measured; zero still rejects zero measurements. */
    @Parameter(property = "shardpilot.minimumCoverage", defaultValue = "0.0")
    private double minimumCoverage;

    /**
     * Validates configuration before reading evidence, then writes a candidate or failed-data report.
     * Maven's goal descriptor deliberately supplies no lifecycle phase, fork or dependency scope.
     *
     * @throws MojoFailureException for invalid configuration or rejected timing evidence
     * @throws MojoExecutionException if output writing or stale-candidate removal fails
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipAggregator()) return;
        try {
            var options = new DurationHistoryRefresh.Options(minimumSeconds, precision,
                    includeFailedSuites, duplicates, minimumCoverage, shardCount);
            Source source = source();
            Path output = outputFile == null ? namespace().resolve("durations.tsv") : outputFile.toPath();
            Path report = reportFile == null ? namespace().resolve("durations.json") : reportFile.toPath();
            List<Path> inputs = reports == null || reports.length == 0
                    ? List.of(Path.of(project.getBuild().getDirectory(),
                            runner == TestRunner.SUREFIRE ? "surefire-reports" : "failsafe-reports"))
                    : Arrays.stream(reports).map(File::toPath).toList();
            // Cleanup on a later data failure must never delete an input through an output alias.
            validateOutputs(output, report, source, inputs);
            write(refresh(source, inputs, options), output, report);
        } catch (IllegalArgumentException failure) {
            throw new MojoFailureException("ShardPilot history configuration: " + failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new MojoExecutionException("Cannot write ShardPilot history artifacts", failure);
        }
    }

    /** Resolves source presence only; readability is evaluated later as reportable data validation. */
    private Source source() {
        boolean classes = classDirectories != null && classDirectories.length > 0;
        return switch (pruning) {
            case AUTO -> {
                if (baselineFile != null && classes) throw new IllegalArgumentException("AUTO cannot combine explicit baselineFile and classDirectories");
                // Unlike a Gradle task, an artifact-only Maven invocation always has a plan namespace.
                yield classes ? Source.CLASS_DIRECTORIES : Source.BASELINE;
            }
            case BASELINE -> {
                if (classes) throw new IllegalArgumentException("BASELINE forbids explicit classDirectories");
                yield Source.BASELINE;
            }
            case CLASS_DIRECTORIES -> {
                if (baselineFile != null) throw new IllegalArgumentException("CLASS_DIRECTORIES forbids explicit baselineFile");
                yield Source.CLASS_DIRECTORIES;
            }
            case DISABLED -> {
                if (baselineFile != null || classes || minimumCoverage > 0) {
                    throw new IllegalArgumentException("DISABLED forbids explicit sources and minimumCoverage > 0");
                }
                yield Source.DISABLED;
            }
        };
    }

    /** Honors an explicit manifest independently of the prepared plan directory override. */
    private Path baseline() {
        return baselineFile == null ? planDirectory().resolve("baseline.yaml") : baselineFile.toPath();
    }

    /** Uses project-local compilation paths without scheduling or creating their producers. */
    private List<File> classRoots() {
        return classDirectories == null || classDirectories.length == 0
                ? List.of(new File(project.getBuild().getTestOutputDirectory())) : List.of(classDirectories);
    }

    /** Reads only the chosen source; an empty source remains distinct from DISABLED pruning. */
    private DurationHistoryRefresh.Eligibility eligibility(Source source) throws IOException {
        if (source == Source.DISABLED) return DurationHistoryRefresh.Eligibility.disabled();
        if (source == Source.BASELINE) return new DurationHistoryRefresh.Eligibility(source, ClassManifest.read(baseline(), true));
        List<File> roots = classRoots();
        var files = new HashSet<File>();
        for (File root : roots) {
            if (!root.isDirectory()) throw new IllegalArgumentException("Missing class directory");
            try (var walk = Files.walk(root.toPath())) {
                walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class"))
                        .map(Path::toFile).forEach(files::add);
            }
        }
        return new DurationHistoryRefresh.Eligibility(source, ClassManifest.compiled(files, roots));
    }

    /**
     * Reads eligibility, old history and XML in that order. An early failure reports only counters
     * known at that phase. Parser exceptions and invalid input contents are never chained into
     * Maven diagnostics; the shared result contains stable reasons and safe metadata instead.
     */
    private DurationHistoryRefresh.Result refresh(Source source, List<Path> inputs, DurationHistoryRefresh.Options options) {
        DurationHistoryRefresh.Eligibility eligible;
        try { eligible = eligibility(source); }
        catch (IOException | IllegalArgumentException failure) {
            return DurationHistoryRefresh.failed(Reason.INVALID_BASELINE,
                    "Missing, unreadable or invalid eligibility source", source, null, options);
        }
        Map<String, Double> previous;
        try { previous = DurationHistory.parse(history()); }
        catch (IOException | IllegalArgumentException failure) {
            return DurationHistoryRefresh.failed(Reason.INVALID_HISTORY, "Invalid previous timing history", source,
                    source == Source.DISABLED ? null : eligible.families().size(), options);
        }
        try { return DurationHistoryRefresh.refresh(previous, JUnitXmlTimings.read(inputs), eligible, options); }
        catch (JUnitXmlTimings.ReadFailure failure) {
            // Keep completed metadata, but never expose XML payloads or a partial TSV as a candidate.
            var partial = DurationHistoryRefresh.refresh(previous, failure.partial(), eligible, options);
            return new DurationHistoryRefresh.Result(Map.of(), partial.report().failedRead(Reason.INVALID_XML, failure.getMessage()));
        }
    }

    /**
     * A data failure removes any earlier successful TSV before publishing FAILED JSON. Writes
     * are not a multi-file transaction: an I/O failure fails Maven even if JSON was already
     * written. Consumers must check the Maven exit status as well as the report status.
     */
    private void write(DurationHistoryRefresh.Result result, Path output, Path report)
            throws IOException, MojoFailureException {
        if (!result.passed()) Files.deleteIfExists(output);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, result.report().json(), StandardCharsets.UTF_8);
        getLog().info(result.report().lifecycle());
        if (!result.passed()) throw new MojoFailureException("ShardPilot history refresh failed: " + result.report().reason());
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, DurationHistory.format(result.history(), precision), StandardCharsets.UTF_8);
    }

    /** Protects maintained inputs and input trees for both successful writes and failure cleanup. */
    private void validateOutputs(Path output, Path report, Source source, List<Path> inputs) {
        if (same(output, report)) throw new IllegalArgumentException("outputFile and reportFile must be distinct");
        var protectedFiles = new HashSet<Path>();
        if (durationsFile != null) protectedFiles.add(durationsFile.toPath());
        if (source == Source.BASELINE) protectedFiles.add(baseline());
        // Protect report/class input trees as well as direct file aliases; failure cleanup deletes TSV.
        var protectedRoots = new HashSet<>(inputs);
        if (source == Source.CLASS_DIRECTORIES) classRoots().stream().map(File::toPath).forEach(protectedRoots::add);
        for (Path candidate : List.of(output, report)) {
            if (protectedFiles.stream().anyMatch(input -> same(candidate, input))
                    || protectedRoots.stream().anyMatch(input -> same(candidate, input) || within(candidate, input) || linkedEntry(candidate, input))) {
                throw new IllegalArgumentException("outputFile/reportFile cannot overwrite history, baseline or report/class inputs");
            }
        }
    }

    /** Covers lexical aliases, existing symbolic links and different paths to the same hardlink. */
    private static boolean same(Path left, Path right) {
        try { return identity(left).equals(identity(right)) || Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right); }
        catch (IOException failure) { return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()); }
    }

    /** Resolves existing parent links even when the candidate output has not been created yet. */
    private static boolean within(Path candidate, Path root) {
        try { return identity(candidate).startsWith(identity(root)); }
        catch (IOException failure) { return candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()); }
    }

    /** Existing output hardlinks may alias files inside a report/class tree outside its path prefix. */
    private static boolean linkedEntry(Path candidate, Path root) {
        if (!Files.exists(candidate) || !Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(input -> same(candidate, input));
        } catch (IOException | java.io.UncheckedIOException failure) {
            // The normal data read reports inaccessible evidence as FAILED JSON.
            return false;
        }
    }

    /** Resolves the nearest existing ancestor without creating any missing output directories. */
    private static Path identity(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        Path parent = absolute.getParent();
        return parent == null ? absolute : identity(parent).resolve(absolute.getFileName());
    }
}
