package io.github.sjacek.shardpilot;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.model.ObjectFactory;
import javax.inject.Inject;

/**
 * Opt-in duration refresh configuration; never schedules the test or compilation producers.
 * Explicit source overrides have no verification conventions attached: the task resolves those
 * separately, preserving both source precedence and order-independent history/verification blocks.
 */
public abstract class ShardHistoryOptions {
    private final Property<Double> minimumSeconds;
    private final Property<Double> minimumCoverage;
    /** Explicit source selection; AUTO honors consumer settings before verification conventions. */
    public enum Pruning {
        /** Explicit overrides, then verification baseline, then owning-task bytecode. */
        AUTO,
        /** Explicit manifest or verification baseline only. */
        BASELINE,
        /** Explicit bytecode roots or owning-task bytecode. */
        CLASS_DIRECTORIES,
        /** No pruning; source overrides and positive coverage thresholds are forbidden. */
        DISABLED
    }
    /**
     * Creates typed numeric providers with concrete getters, permitting Groovy Number setters.
     * Gradle cannot synthesize an abstract managed Property getter when a concrete bean setter
     * already exists; allocating these two properties preserves the typed Kotlin/provider API.
     * @param objects Gradle's managed property factory
     */
    @Inject
    public ShardHistoryOptions(ObjectFactory objects) {
        minimumSeconds = objects.property(Double.class);
        minimumCoverage = objects.property(Double.class);
    }
    /**
     * Report roots, defaulting to the owning task's JUnit XML output directory.
     * @return report roots, defaulting to the owning task's JUnit XML output directory
     */
    public abstract ConfigurableFileCollection getReports();
    /**
     * Task-owned candidate TSV, never the versioned durationsFile.
     * @return task-owned candidate TSV, never the versioned durationsFile
     */
    public abstract RegularFileProperty getOutputFile();
    /**
     * JSON evidence written on success and data failures.
     * @return JSON evidence written on success and data failures
     */
    public abstract RegularFileProperty getReportFile();
    /**
     * Minimum accepted family time in seconds, default 0.1.
     * @return minimum accepted family time in seconds, default 0.1
     */
    public Property<Double> getMinimumSeconds() { return minimumSeconds; }

    /**
     * Accepts natural Groovy decimal literals, which arrive as BigDecimal rather than Double.
     * @param seconds numeric family-time floor; finite/positive validation occurs in the task
     */
    @SuppressWarnings("unused") // Groovy property assignment calls this setter dynamically; covered by TestKit.
    public void setMinimumSeconds(Number seconds) { getMinimumSeconds().set(seconds.doubleValue()); }
    /**
     * Decimal fractional digits, 0–6, default 1.
     * @return decimal fractional digits, 0–6, default 1
     */
    public abstract Property<Integer> getPrecision();
    /**
     * Whether non-retried failed suites can be measured; default false.
     * @return whether non-retried failed suites can be measured; default false
     */
    public abstract Property<Boolean> getIncludeFailedSuites();
    /**
     * Exact suite-name duplicate policy, default FAIL.
     * @return exact suite-name duplicate policy, default FAIL
     */
    public abstract Property<DurationHistoryRefresh.Duplicates> getDuplicates();
    /**
     * Minimum family coverage, 0–1, default 0.
     * @return minimum family coverage, 0–1, default 0
     */
    public Property<Double> getMinimumCoverage() { return minimumCoverage; }

    /**
     * Accepts natural Groovy decimal literals without changing the typed provider API.
     * @param coverage numeric eligible-family fraction; range validation occurs in the task
     */
    @SuppressWarnings("unused") // Groovy property assignment calls this setter dynamically; covered by TestKit.
    public void setMinimumCoverage(Number coverage) { getMinimumCoverage().set(coverage.doubleValue()); }
    /**
     * Source-selection policy, default AUTO.
     * @return source-selection policy, default AUTO
     */
    public abstract Property<Pruning> getPruning();
    /**
     * Explicit manifest override, without a verification convention.
     * @return explicit manifest override, without a verification convention
     */
    public abstract RegularFileProperty getBaselineFile();
    /**
     * Explicit bytecode roots; empty means use source-selection defaults.
     * @return explicit bytecode roots; empty means use source-selection defaults
     */
    public abstract ConfigurableFileCollection getClassDirectories();
}
