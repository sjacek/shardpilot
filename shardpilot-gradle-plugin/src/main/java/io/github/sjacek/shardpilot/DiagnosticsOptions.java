package io.github.sjacek.shardpilot;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Opt-in C4 settings; no native tasks, command execution or Docker connections during configuration. */
@SuppressWarnings("unused") // Managed public Gradle DSL, exercised by Groovy/Kotlin TestKit consumers.
public abstract class DiagnosticsOptions {
    /** Creates managed defaults; identity and Docker ownership must be supplied by the consumer. */
    public DiagnosticsOptions() {
        getTimeoutMillis().convention(0L); getSampleIntervalMillis().convention(1000L); getMaxSamples().convention(10_000);
        getHostMemory().convention(false); getDockerCommand().convention(java.util.List.of());
        getDockerHost().convention(""); getDedicatedDocker().convention(false);
    }
    /** Private settings destination.
     * @return configuration file outside the session
     */
    public abstract RegularFileProperty getConfigurationFile();
    /** Single-use retained evidence.
     * @return exclusively owned session directory
     */
    public abstract DirectoryProperty getDirectory();
    /** Expected source identity.
     * @return full lowercase Git revision
     */
    public abstract Property<String> getRevision();
    /** Expected CI cohort.
     * @return CI run identifier
     */
    public abstract Property<String> getRunId();
    /** Retry isolation.
     * @return positive attempt number
     */
    public abstract Property<Integer> getAttempt();
    /** Expected suite identity.
     * @return suite name
     */
    public abstract Property<String> getSuite();
    /** Parallel execution identity.
     * @return job/shard name
     */
    public abstract Property<String> getNode();
    /** Native execution deadline.
     * @return timeout in milliseconds; zero disables it
     */
    public abstract Property<Long> getTimeoutMillis();
    /** Sampling frequency.
     * @return interval between retained memory samples in milliseconds
     */
    public abstract Property<Long> getSampleIntervalMillis();
    /** Bounds retained measurements without disabling supervision.
     * @return maximum samples
     */
    public abstract Property<Integer> getMaxSamples();
    /** Job memory scope.
     * @return optional explicitly scoped cgroup directory
     */
    public abstract DirectoryProperty getJobCgroup();
    /** Separate Docker-in-Docker memory scope.
     * @return optional explicitly scoped cgroup directory
     */
    public abstract DirectoryProperty getDindCgroup();
    /** Optional visible host counters.
     * @return whether host memory sampling is enabled
     */
    public abstract Property<Boolean> getHostMemory();
    /** Read-only Docker client invocation.
     * @return executable/prefix; empty disables checks
     */
    public abstract ListProperty<String> getDockerCommand();
    /** Explicit dedicated daemon.
     * @return endpoint, never inferred from the environment
     */
    public abstract Property<String> getDockerHost();
    /** No shared-daemon inspection.
     * @return consumer declaration of exclusive daemon ownership
     */
    public abstract Property<Boolean> getDedicatedDocker();

    Diagnostics.Settings settings() {
        return new Diagnostics.Settings(new Diagnostics.Identity(getRevision().get(), getRunId().get(), getAttempt().get(), getSuite().get(), getNode().get()),
                getDirectory().get().getAsFile().toPath(), getTimeoutMillis().get(), getSampleIntervalMillis().get(), getMaxSamples().get(),
                getJobCgroup().isPresent() ? getJobCgroup().get().getAsFile().toPath() : null,
                getDindCgroup().isPresent() ? getDindCgroup().get().getAsFile().toPath() : null, getHostMemory().get(),
                getDockerCommand().get(), getDockerHost().get(), getDedicatedDocker().get());
    }
}
