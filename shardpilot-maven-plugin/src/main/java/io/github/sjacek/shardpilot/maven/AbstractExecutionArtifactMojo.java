package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.CompiledArtifacts;
import io.github.sjacek.shardpilot.ExecutionSelection;
import java.io.File;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/** Shared optional C3 configuration; declaring a verifier never creates producer dependencies. */
@NullMarked
abstract class AbstractExecutionArtifactMojo extends AbstractMojo {
    /** Retained C3 v1 manifest; leaving it unset disables this independent gate. */
    @Parameter(property = "shardpilot.executionCompiledManifestFile")
    private @Nullable File compiledManifestFile;

    /** Actual transferred compilation inputs, not a policy inferred from the manifest. */
    @Parameter(property = "shardpilot.executionCompiledBaseDirectory")
    private @Nullable File compiledBaseDirectory;

    /** Independently configured C3 revision; producers can default to their trusted selection revision. */
    @Parameter(property = "shardpilot.executionCompiledRevision")
    private @Nullable String compiledRevision;

    /** Independently configured C3 suite. */
    @Parameter(property = "shardpilot.executionCompiledSuite")
    private @Nullable String compiledSuite;

    /** Disjoint root-relative trees; use the POM list for several roots. */
    @Parameter(property = "shardpilot.executionCompiledRoots")
    private @Nullable List<String> compiledRoots = List.of();

    /** Required directories containing at least one nonempty file. */
    @Parameter(property = "shardpilot.executionCompiledRequiredDirectories")
    private @Nullable List<String> compiledRequiredDirectories = List.of();

    /** Independently required nonempty files. */
    @Parameter(property = "shardpilot.executionCompiledRequiredFiles")
    private @Nullable List<String> compiledRequiredFiles = List.of();

    /** Optional separately pinned manifest digest. */
    @Parameter(property = "shardpilot.executionCompiledManifestSha256", defaultValue = "")
    private @Nullable String compiledManifestSha256 = "";

    /** Lowerable C3 regular-file ceiling. */
    @Parameter(property = "shardpilot.executionCompiledMaxFiles", defaultValue = "100000")
    private int compiledMaxFiles = 100_000;

    /** Lowerable C3 entry ceiling, including directories. */
    @Parameter(property = "shardpilot.executionCompiledMaxEntries", defaultValue = "200000")
    private int compiledMaxEntries = 200_000;

    /** Lowerable C3 depth ceiling. */
    @Parameter(property = "shardpilot.executionCompiledMaxDepth", defaultValue = "64")
    private int compiledMaxDepth = 64;

    /** Lowerable maximum streamed file size. */
    @Parameter(property = "shardpilot.executionCompiledMaxFileBytes", defaultValue = "1073741824")
    private long compiledMaxFileBytes = 1_073_741_824L;

    /** Lowerable total streamed bytes. */
    @Parameter(property = "shardpilot.executionCompiledMaxTotalBytes", defaultValue = "8589934592")
    private long compiledMaxTotalBytes = 8_589_934_592L;

    /** Lowerable C3 manifest byte ceiling. */
    @Parameter(property = "shardpilot.executionCompiledMaxManifestBytes", defaultValue = "33554432")
    private int compiledMaxManifestBytes = 33_554_432;

    /**
     * Builds optional trusted C3 inputs. A producer supplies its independently configured identity as
     * defaults; the aggregate passes nulls and must configure identity explicitly. Any partial location,
     * identity, requirement or pin configuration fails rather than silently disabling the C3 gate.
     * No root, revision or suite is inferred from downloaded metadata.
     */
    ExecutionSelection.@Nullable Compiled compiledInputs(@Nullable String revision, @Nullable String suite) {
        // Maven may inject null for unresolved optional list expressions, despite field initializers.
        var roots = compiledRoots == null ? List.<String>of() : compiledRoots;
        var directories = compiledRequiredDirectories == null ? List.<String>of() : compiledRequiredDirectories;
        var files = compiledRequiredFiles == null ? List.<String>of() : compiledRequiredFiles;
        String pin = compiledManifestSha256 == null ? "" : compiledManifestSha256;
        if (compiledManifestFile == null && compiledBaseDirectory == null && roots.isEmpty()
                && directories.isEmpty() && files.isEmpty() && compiledRevision == null
                && compiledSuite == null && pin.isEmpty()) return null;
        String expectedRevision = compiledRevision == null ? revision : compiledRevision;
        String expectedSuite = compiledSuite == null ? suite : compiledSuite;
        if (compiledManifestFile == null || compiledBaseDirectory == null || expectedRevision == null || expectedSuite == null) {
            throw new IllegalArgumentException("Incomplete compiled execution inputs");
        }
        var limits = new CompiledArtifacts.Limits(compiledMaxFiles, compiledMaxEntries, compiledMaxDepth,
                compiledMaxFileBytes, compiledMaxTotalBytes, compiledMaxManifestBytes);
        return new ExecutionSelection.Compiled(new CompiledArtifacts.Inputs(compiledBaseDirectory.toPath(), expectedRevision, expectedSuite,
                roots, directories, files, limits), compiledManifestFile.toPath(), pin);
    }
}
