package io.github.sjacek.shardpilot.maven;

/**
 * Eligibility selection for an artifact-only history refresh; never schedules compilation.
 * A selected manifest/directory must be readable, even if it contains no eligible families.
 * Only DISABLED deliberately omits the eligible set and its coverage denominator.
 */
public enum HistoryPruning {
    /** Explicit baseline or class roots, otherwise the suite's planned baseline; missing data fails. */
    AUTO,
    /** Explicit baseline or the suite's planned baseline; explicit class roots are forbidden. */
    BASELINE,
    /** Explicit class roots or the project's test output directory; explicit baseline is forbidden. */
    CLASS_DIRECTORIES,
    /** Keep every family; explicit sources and a positive minimum coverage are forbidden. */
    DISABLED
}
