package io.github.sjacek.shardpilot.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Project-local parameters shared by planning, selection, verification and history refresh.
 * Path defaults are derived lazily from this invocation's runner/suite, so parallel reactor
 * projects never publish selection through mutable MavenSession properties. Explicit goals use
 * common plugin configuration; a named selection execution's overrides are not global defaults.
 */
public abstract class AbstractShardMojo extends AbstractMojo {
    /** Creates a Maven-managed goal; the container injects project/session parameters before execution. */
    protected AbstractShardMojo() { }
    /** Current reactor project; POM-only aggregator modules are skipped. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /** Resolves effective native-runner expressions, including command-line user properties. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /** Parameter-expression evaluation context, never cached between projects. */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    protected MojoExecution mojoExecution;

    /** Native runner whose class filters are used for eligibility. */
    @Parameter(property = "shardpilot.runner", defaultValue = "SUREFIRE")
    protected TestRunner runner = TestRunner.SUREFIRE;

    /** Positive number of independent CI nodes. */
    @Parameter(property = "shardpilot.count", defaultValue = "1")
    protected int shardCount = 1;

    /** One-based current node; preparation, verification and history refresh only require count. */
    @Parameter(property = "shardpilot.index", defaultValue = "1")
    protected int shardIndex = 1;

    /** Optional TSV history; an unset/missing file means deterministic hash-only assignment. */
    @Parameter(property = "shardpilot.durationsFile")
    protected File durationsFile;

    /** Optional suite namespace, defaulting to unit or integration for the chosen runner. */
    @Parameter(property = "shardpilot.suite")
    protected String suite;

    /** Optional base namespace; defaults to target/shardpilot/SUITE for this project. */
    @Parameter(property = "shardpilot.directory")
    protected File directory;

    /** Optional directory of baseline.yaml and planned-N.yaml. */
    @Parameter(property = "shardpilot.plannedDirectory")
    protected File plannedDirectory;

    /** Optional directory of actual-N.yaml selections collected from every CI node. */
    @Parameter(property = "shardpilot.actualDirectory")
    protected File actualDirectory;

    /** Generated native exclusions; configure the runner's excludesFile to this same path. */
    @Parameter(property = "shardpilot.excludesFile")
    protected File excludesFile;

    /** Original consumer exclusions file, merged with native patterns before sharding. */
    @Parameter(property = "shardpilot.additionalExcludesFile")
    protected File additionalExcludesFile;

    /**
     * A POM-only parent has no native class suite and must not create misleading empty artifacts.
     * @return whether the current project is an aggregator that should be skipped
     */
    protected boolean skipAggregator() {
        if (!"pom".equals(project.getPackaging())) return false;
        getLog().debug("ShardPilot skips POM-only aggregator " + project.getArtifactId());
        return true;
    }

    /**
     * Resolves the project/suite namespace without creating directories. A custom suite remains
     * one safe path component even when the base directory is overridden independently.
     * @return configured base directory or the current project's default suite namespace
     */
    protected Path namespace() {
        String name = suite == null ? runner.suite : suite;
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("ShardPilot suite must be a single alphanumeric path component");
        }
        return directory != null ? directory.toPath() : Path.of(project.getBuild().getDirectory(), "shardpilot", name);
    }

    /**
     * Plan overrides are independent of the actual-selection and report paths.
     * @return baseline/planned manifest directory
     */
    protected Path planDirectory() { return plannedDirectory == null ? namespace().resolve("plan") : plannedDirectory.toPath(); }

    /**
     * Stores one index-specific selection per node; never used as a source of eligibility.
     * @return actual-manifest directory
     */
    protected Path selectionDirectory() { return actualDirectory == null ? namespace().resolve("actual") : actualDirectory.toPath(); }

    /**
     * Generated output consumed by the native runner, distinct from maintained consumer exclusions.
     * @return native runner's generated exclusion file
     */
    protected Path exclusions() { return excludesFile == null ? namespace().resolve("excludes.txt") : excludesFile.toPath(); }

    /**
     * Missing optional history is a cold start; unreadable existing history remains an I/O error.
     * @return UTF-8 TSV metadata, or an empty string for a cold start
     * @throws IOException if an existing history file cannot be read
     */
    protected String history() throws IOException {
        return durationsFile == null || !durationsFile.exists() ? "" : Files.readString(durationsFile.toPath());
    }
}
