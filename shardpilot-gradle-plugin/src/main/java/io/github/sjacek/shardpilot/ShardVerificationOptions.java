package io.github.sjacek.shardpilot;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Explicit, framework-independent inputs for one Test task's optional coverage gates. */
public abstract class ShardVerificationOptions {
    /** Creates options managed by Gradle's object factory. */
    public ShardVerificationOptions() { }

    /**
     * Supplies unsharded eligible class files after application-owned include/exclude rules; required.
     * @return the configured value
     */
    public abstract ConfigurableFileCollection getEligibleClasses();

    /**
     * Supplies bytecode roots used to derive binary class names; defaults to the owning task's roots.
     * @return the configured value
     */
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Supplies partition size for preparation/verification, independent of the current CI node index.
     * @return the configured value
     */
    public abstract Property<Integer> getShardCount();

    /**
     * Supplies directory containing baseline.yaml and planned-N.yaml artifacts.
     * @return the configured value
     */
    public abstract DirectoryProperty getPlannedDirectory();

    /**
     * Supplies directory containing actual-N.yaml artifacts downloaded from every CI node.
     * @return the configured value
     */
    public abstract DirectoryProperty getActualDirectory();

    /**
     * Supplies JSON coverage report, written on both successful and failed verification.
     * @return the configured value
     */
    public abstract RegularFileProperty getReportFile();

    /**
     * Supplies the standalone HTML assignment report, written on success and failure.
     * @return the configured value; defaults to coverage.html in the task's verification namespace
     */
    public abstract RegularFileProperty getHtmlReportFile();

    /**
     * Supplies whether actual CI selections are required; defaults to shardpilot.verifyActual, then true.
     * @return the configured value
     */
    public abstract Property<Boolean> getVerifyActual();

    /**
     * Supplies whether an empty shard is rejected; defaults to true; the baseline must always be nonempty.
     * @return the configured value
     */
    public abstract Property<Boolean> getRequireNonEmptyShards();
}
