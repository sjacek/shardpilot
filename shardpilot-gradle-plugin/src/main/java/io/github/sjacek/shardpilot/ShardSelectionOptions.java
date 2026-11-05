package io.github.sjacek.shardpilot;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * Explicit selection inputs for one owning Test task. Eligibility describes the full native class
 * boundary before subset/ownership exclusions; using candidateClassFiles here would feed the output
 * back into validation. The consumer chooses the subset and owns its risk/impact policy.
 */
public abstract class ShardSelectionOptions {
    /** Creates Gradle-managed options. */
    public ShardSelectionOptions() { }

    /**
     * Supplies required strict v1 YAML selection. File contents participate in task/cache inputs;
     * a missing or invalid file fails instead of disabling the subset or selecting all classes.
     * @return the configured value
     */
    public abstract RegularFileProperty getManifestFile();

    /**
     * Supplies a required safe path component, distinct from the reserved full-suite identity.
     * This namespace separates plans, diagnostics and native results from the full suite.
     * @return the configured value
     */
    public abstract Property<String> getSuite();

    /**
     * Supplies full unsharded eligible bytecode, including consumer-owned discovery restrictions.
     * Source-set file collections retain compilation dependencies; no test class is loaded to scan it.
     * @return the configured value
     */
    public abstract ConfigurableFileCollection getEligibleClasses();

    /**
     * Supplies compilation roots; defaults to the owning Test task's roots.
     * @return the configured value
     */
    public abstract ConfigurableFileCollection getClassDirectories();
}
