package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.TestSelection;
import io.github.sjacek.shardpilot.ClassManifest;
import io.github.sjacek.shardpilot.DurationHistory;
import io.github.sjacek.shardpilot.GitChangeOptions;
import io.github.sjacek.shardpilot.GitChanges;
import io.github.sjacek.shardpilot.ProfileSelection;
import io.github.sjacek.shardpilot.SelectionContext;
import io.github.sjacek.shardpilot.SelectionPolicy;
import io.github.sjacek.shardpilot.SelectionPolicyYaml;
import java.util.Set;

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

    /** Optional strict v1 YAML subset; absent preserves existing full-suite behavior. */
    @Parameter(property = "shardpilot.selectionManifestFile")
    protected File selectionManifestFile;

    /** Separate identity for explicit manifests; profiles default to profile-NAME. */
    @Parameter(property = "shardpilot.selectionSuite")
    protected String selectionSuite;

    /** Shared version-1 policy; paired with selectionProfile. */
    @Parameter(property = "shardpilot.selectionPolicyFile")
    protected File selectionPolicyFile;

    /** Named policy profile; its default namespace is profile-NAME. */
    @Parameter(property = "shardpilot.selectionProfile")
    protected String selectionProfile;

    /** Read-only planning snapshot downloaded outside runtime outputs; required for multi-node execution. */
    @Parameter(property = "shardpilot.selectionSnapshotDirectory")
    protected File selectionSnapshotDirectory;

    /** Checkout or a directory within it, discovered independently for each reactor project. */
    @Parameter(property = "shardpilot.gitDirectory", defaultValue = "${project.basedir}")
    protected File gitDirectory;

    /** Explicit comparison base; empty enables supported CI inference. */
    @Parameter(property = "shardpilot.gitBase", defaultValue = "")
    protected String gitBase = "";

    /** Commit-ish that must resolve to the actual checked-out HEAD. */
    @Parameter(property = "shardpilot.gitHead", defaultValue = "HEAD")
    protected String gitHead = "HEAD";

    /** Compare the complete branch contribution against its common ancestor. */
    @Parameter(property = "shardpilot.gitMergeBase", defaultValue = "true")
    protected boolean gitMergeBase = true;

    /** Explicit permission for bounded fetch; never effective in Maven offline mode. */
    @Parameter(property = "shardpilot.gitFetch", defaultValue = "false")
    protected boolean gitFetch;

    /** Configured remote name; URLs are not accepted as parameter values. */
    @Parameter(property = "shardpilot.gitRemote", defaultValue = "origin")
    protected String gitRemote = "origin";

    /** Maximum requested history depth when fetch is explicitly enabled. */
    @Parameter(property = "shardpilot.gitFetchDepth", defaultValue = "256")
    protected int gitFetchDepth = 256;

    /** Per-command time limit, in seconds. */
    @Parameter(property = "shardpilot.gitTimeoutSeconds", defaultValue = "30")
    protected int gitTimeoutSeconds = 30;

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
        Path base = directory != null ? directory.toPath() : Path.of(project.getBuild().getDirectory(), "shardpilot", name);
        return selectedNamespace(base);
    }

    /**
     * Isolates explicit overrides as well as defaults from the corresponding full-suite outputs.
     * @param base consumer-owned output directory
     * @return unchanged full-suite path or its selection-specific child
     */
    protected Path selectedNamespace(Path base) {
        boolean profile = profileConfigured();
        if (!profile && selectionManifestFile == null && selectionSuite == null) return base;
        if (!profile && selectionManifestFile == null) throw new IllegalArgumentException("selectionSuite requires selectionManifestFile");
        String selectedSuite = selectionSuite != null ? selectionSuite : profile ? ProfileSelection.defaultSuite(selectionProfile) : null;
        TestSelection.validateSuite(selectedSuite);
        return base.resolve("selections").resolve(selectedSuite);
    }

    /**
     * Resolves opt-in without invoking producers, including in standalone verification.
     * @return whether either explicit or policy-based selection was configured
     */
    protected boolean selectionActive() { return profileConfigured() || selectionManifestFile != null; }

    private boolean profileConfigured() {
        // This is intentionally structural. Standalone verify resolves the namespace without reading
        // policy, compiled classes or Git; missing evidence must not schedule a producer to recreate it.
        if ((selectionPolicyFile == null) != (selectionProfile == null)) {
            throw new IllegalArgumentException("selectionPolicyFile and selectionProfile must be supplied together");
        }
        if (selectionSnapshotDirectory != null && selectionPolicyFile == null) {
            throw new IllegalArgumentException("selectionSnapshotDirectory requires profile selection");
        }
        return selectionPolicyFile != null;
    }

    /**
     * Adapts one common decision or reuses its immutable snapshot; native discovery remains independent.
     * @param eligible current native eligibility
     * @param history exact history snapshot for this invocation
     * @param planning whether this is the explicit planning goal
     * @return selected cohort, or null for legacy full-suite behavior
     * @throws IOException if maintained inputs or generated evidence cannot be accessed
     */
    protected TestSelection configuredSelection(Set<String> eligible, String history, boolean planning) throws IOException {
        if (!profileConfigured()) return explicitSelection(eligible);
        Path output = namespace().resolve("selection");
        for (File input : new File[]{selectionPolicyFile, selectionManifestFile}) {
            if (input == null) continue;
            Path source = input.toPath().toAbsolutePath().normalize();
            for (Path target : java.util.List.of(namespace(), planDirectory(), selectionDirectory())) {
                if (source.startsWith(target.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("Keep maintained profile inputs outside generated selection outputs");
                }
            }
            TestSelection.protectInput(source, exclusions(), output.resolve("selected.yaml"), output.resolve("eligible.yaml"),
                    output.resolve("selection.id"), output.resolve("selection.json"), output.resolve("selection.html"),
                    output.resolve("decision/selected.yaml"), output.resolve("decision/eligible.yaml"), output.resolve("decision/decision.id"),
                    output.resolve("decision/decision.json"), output.resolve("decision/decision.html"), output.resolve("decision/git.json"));
        }
        if (selectionSnapshotDirectory != null) {
            for (Path target : java.util.List.of(namespace(), planDirectory(), selectionDirectory())) {
                ProfileSelection.protectSnapshot(selectionSnapshotDirectory.toPath(), target);
            }
        }
        // Fail closed before parsing so an unreadable/replaced policy cannot leave an executable
        // snapshot from an earlier invocation. Old native evidence remains available for diagnostics.
        ProfileSelection.invalidate(output);
        var policy = SelectionPolicyYaml.read(selectionPolicyFile.toPath());
        var explicit = selectionManifestFile == null ? Set.<String>of() : ClassManifest.read(selectionManifestFile.toPath(), false);
        String selectedSuite = selectionSuite == null ? ProfileSelection.defaultSuite(selectionProfile) : selectionSuite;
        Path checkout = gitDirectory == null ? project.getBasedir().toPath() : gitDirectory.toPath();
        if (selectionSnapshotDirectory != null) {
            return ProfileSelection.restore(selectionSnapshotDirectory.toPath(), output, policy, selectionProfile,
                    eligible, explicit, selectedSuite, history, shardCount, checkout);
        }
        if (!planning && shardCount > 1) {
            // Only the planning invocation may query mutable refs for a multi-node cohort.
            throw new IllegalArgumentException("Multi-shard profile execution requires selectionSnapshotDirectory from one planning job");
        }
        var profile = policy.profiles().get(selectionProfile);
        GitChanges.Result changes = profile != null && profile.mode() == SelectionPolicy.Mode.AFFECTED
                ? GitChanges.acquire(new GitChangeOptions(checkout, gitBase, gitHead, gitMergeBase,
                    gitFetch && (session == null || !session.isOffline()), gitRemote, gitFetchDepth, gitTimeoutSeconds), System.getenv())
                : GitChanges.notRequired();
        var context = new SelectionContext(selectionProfile, eligible, changes.changes(), DurationHistory.parse(history), explicit);
        return ProfileSelection.prepare(output, policy, context, changes, selectedSuite, history, shardCount);
    }

    /**
     * Reads the consumer manifest against independently discovered native eligibility.
     * Protection checks precede the failure-report handler: even a diagnostic must not overwrite
     * the maintained input. Parsed validation failures invalidate the reusable snapshot only;
     * older plan/actual artifacts are retained as evidence, and the failed native job remains fatal.
     * @param eligible complete runner candidates
     * @return validated selection or null for the original full suite
     * @throws IOException if the manifest cannot be read
     */
    protected TestSelection explicitSelection(Set<String> eligible) throws IOException {
        namespace();
        if (selectionManifestFile == null) return null;
        Path source = selectionManifestFile.toPath().toAbsolutePath().normalize();
        for (Path output : java.util.List.of(namespace(), planDirectory(), selectionDirectory())) {
            if (source.startsWith(output.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Keep the selection manifest outside generated selection outputs");
            }
        }
        var diagnostics = namespace().resolve("selection");
        TestSelection.protectInput(selectionManifestFile.toPath(), exclusions(),
                diagnostics.resolve("selected.yaml"), diagnostics.resolve("eligible.yaml"), diagnostics.resolve("selection.id"),
                diagnostics.resolve("selection.json"), diagnostics.resolve("selection.html"), planDirectory().resolve("baseline.yaml"),
                planDirectory().resolve("selection.id"), selectionDirectory().resolve("actual-" + shardIndex + ".yaml"),
                selectionDirectory().resolve("selection-" + shardIndex + ".id"));
        try {
            return TestSelection.read(selectionManifestFile.toPath(), eligible, selectionSuite);
        } catch (IllegalArgumentException failure) {
            TestSelection.writeFailure(diagnostics, failure.getMessage());
            throw failure;
        }
    }

    /**
     * Plan overrides are independent of the actual-selection and report paths.
     * @return baseline/planned manifest directory
     */
    protected Path planDirectory() { return plannedDirectory == null ? namespace().resolve("plan") : selectedNamespace(plannedDirectory.toPath()); }

    /**
     * Stores one index-specific selection per node; never used as a source of eligibility.
     * @return actual-manifest directory
     */
    protected Path selectionDirectory() { return actualDirectory == null ? namespace().resolve("actual") : selectedNamespace(actualDirectory.toPath()); }

    /**
     * Generated output consumed by the native runner, distinct from maintained consumer exclusions.
     * @return native runner's generated exclusion file
     */
    protected Path exclusions() {
        return excludesFile == null ? namespace().resolve("excludes.txt")
                : selectedNamespace(excludesFile.toPath().toAbsolutePath().getParent()).resolve(excludesFile.getName());
    }

    /**
     * Missing optional history is a cold start; unreadable existing history remains an I/O error.
     * @return UTF-8 TSV metadata, or an empty string for a cold start
     * @throws IOException if an existing history file cannot be read
     */
    protected String history() throws IOException {
        return durationsFile == null || !durationsFile.exists() ? "" : Files.readString(durationsFile.toPath());
    }
}
