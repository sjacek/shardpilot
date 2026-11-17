package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.CompiledArtifacts;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/** C3 parameters are independent of native selection and do not resolve project dependencies. */
public abstract class AbstractCompiledArtifactsMojo extends AbstractMojo {
    /** Creates Maven-managed configuration. */
    protected AbstractCompiledArtifactsMojo() { }
    /** Base for portable root-relative paths; specify explicitly when running without a POM. */
    @Parameter(property = "shardpilot.compiledBaseDirectory", defaultValue = "${project.basedir}", required = true)
    protected File compiledBaseDirectory;
    /** Manifest outside every inventoried tree. */
    @Parameter(property = "shardpilot.compiledManifestFile", defaultValue = "${project.build.directory}/shardpilot/compiled-artifacts.json", required = true)
    protected File compiledManifestFile;
    /** Expected full lowercase commit, independently supplied by trusted CI metadata. */
    @Parameter(property = "shardpilot.compiledRevision", required = true)
    protected String compiledRevision;
    /** Independent identity; separate executions should configure separate manifests. */
    @Parameter(property = "shardpilot.compiledSuite", required = true)
    protected String compiledSuite;
    /** Existing, disjoint relative directories, without wildcard expansion. */
    @Parameter(property = "shardpilot.compiledRoots", required = true)
    protected List<String> compiledRoots = List.of();
    /** Relative directories requiring a nonempty file. */
    @Parameter(property = "shardpilot.compiledRequiredDirectories")
    protected List<String> compiledRequiredDirectories = List.of();
    /** Relative files requiring nonempty bytes. */
    @Parameter(property = "shardpilot.compiledRequiredFiles")
    protected List<String> compiledRequiredFiles = List.of();
    /** Optional digest supplied separately from downloaded producer metadata. */
    @Parameter(property = "shardpilot.compiledManifestSha256")
    protected String compiledManifestSha256 = "";
    /** Maximum regular files. */
    @Parameter(property = "shardpilot.compiledMaxFiles", defaultValue = "100000") protected int compiledMaxFiles = 100_000;
    /** Maximum entries including empty directories. */
    @Parameter(property = "shardpilot.compiledMaxEntries", defaultValue = "200000") protected int compiledMaxEntries = 200_000;
    /** Maximum relative depth. */
    @Parameter(property = "shardpilot.compiledMaxDepth", defaultValue = "64") protected int compiledMaxDepth = 64;
    /** Maximum streamed bytes per file. */
    @Parameter(property = "shardpilot.compiledMaxFileBytes", defaultValue = "1073741824") protected long compiledMaxFileBytes = 1_073_741_824L;
    /** Maximum streamed bytes over the entire inventory. */
    @Parameter(property = "shardpilot.compiledMaxTotalBytes", defaultValue = "8589934592") protected long compiledMaxTotalBytes = 8_589_934_592L;
    /** Maximum JSON bytes. */
    @Parameter(property = "shardpilot.compiledMaxManifestBytes", defaultValue = "33554432") protected int compiledMaxManifestBytes = 33_554_432;

    /**
     * Both standalone goals delegate to the same core used by Gradle. Maven enforces required
     * parameters before this method; an integrity failure never becomes a successful native test gate.
     * @param seal whether to create the producer manifest rather than verify retained artifacts
     * @throws MojoFailureException if the cohort, input bytes or configured limits are invalid
     * @throws MojoExecutionException if the producer manifest cannot be written
     */
    protected final void run(boolean seal) throws MojoFailureException, MojoExecutionException {
        try {
            var limits = new CompiledArtifacts.Limits(compiledMaxFiles, compiledMaxEntries, compiledMaxDepth,
                    compiledMaxFileBytes, compiledMaxTotalBytes, compiledMaxManifestBytes);
            // Unresolved optional Maven expressions may arrive as null. Normalize only optional
            // requirements; missing required identity/root inputs must not acquire permissive defaults.
            var inputs = new CompiledArtifacts.Inputs(compiledBaseDirectory.toPath(), compiledRevision, compiledSuite,
                    compiledRoots, compiledRequiredDirectories == null ? List.of() : compiledRequiredDirectories,
                    compiledRequiredFiles == null ? List.of() : compiledRequiredFiles, limits);
            var result = seal ? CompiledArtifacts.seal(inputs, compiledManifestFile.toPath())
                    : CompiledArtifacts.verify(inputs, compiledManifestFile.toPath(), compiledManifestSha256 == null ? "" : compiledManifestSha256);
            getLog().info("ShardPilot C3 " + (seal ? "sealed " : "verified ") + result.files() + " files, " + result.bytes()
                    + " bytes; manifest SHA-256 " + result.manifestSha256());
        } catch (CompiledArtifacts.Problem failure) {
            // Preserve the stable core reason without chaining paths or resource/parser payloads.
            throw new MojoFailureException(failure.getMessage());
        } catch (IOException failure) {
            throw new MojoExecutionException("Cannot write ShardPilot C3 manifest");
        }
    }
}
