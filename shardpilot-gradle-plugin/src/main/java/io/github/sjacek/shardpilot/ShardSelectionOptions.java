package io.github.sjacek.shardpilot;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * Explicit or policy-based selection inputs for one owning Test task. Eligibility describes the full native class
 * boundary before subset/ownership exclusions; using candidateClassFiles here would feed the output
 * back into validation. Portable application mappings belong in the declarative policy.
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
     * Supplies the common version-1 policy, paired with profile. Manifest input is then only for explicit mode.
     * @return optional policy file, defaulting to shardpilot.selectionPolicyFile
     */
    public abstract RegularFileProperty getPolicyFile();

    /**
     * Chooses the named profile within policyFile.
     * @return optional name, defaulting to shardpilot.selectionProfile
     */
    public abstract Property<String> getProfile();

    /**
     * Supplies one immutable planning snapshot outside generated runtime outputs.
     * @return optional downloaded directory, required for multi-shard profile execution
     */
    public abstract DirectoryProperty getSnapshotDirectory();

    /**
     * Supplies a checkout directory, which may be inside the repository root.
     * @return checkout; defaults to the project directory
     */
    public abstract DirectoryProperty getGitDirectory();

    /**
     * Overrides supported CI base inference.
     * @return base revision, empty by default
     */
    public abstract Property<String> getGitBase();

    /**
     * Resolves the actual checked-out revision; a different checkout fails planning.
     * @return HEAD by default
     */
    public abstract Property<String> getGitHead();

    /**
     * Chooses common-ancestor comparison instead of a direct two-commit diff.
     * @return true by default
     */
    public abstract Property<Boolean> getGitMergeBase();

    /**
     * Explicitly permits bounded fetching; disabled by Gradle offline mode.
     * @return false by default
     */
    public abstract Property<Boolean> getGitFetch();

    /**
     * Selects a configured remote name, not a URL.
     * @return origin by default
     */
    public abstract Property<String> getGitRemote();

    /**
     * Limits explicitly requested fetch depth.
     * @return 256 by default
     */
    public abstract Property<Integer> getGitFetchDepth();

    /**
     * Bounds each Git command.
     * @return 30 seconds by default
     */
    public abstract Property<Integer> getGitTimeoutSeconds();

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
