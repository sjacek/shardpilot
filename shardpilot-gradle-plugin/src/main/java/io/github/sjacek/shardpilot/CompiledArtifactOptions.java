package io.github.sjacek.shardpilot;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Consumer-owned, root-relative C3 configuration shared by sealing and both pre-test checks. */
@SuppressWarnings("unused") // Managed Gradle DSL: Groovy/Kotlin consumers and task actions are exercised by TestKit.
public abstract class CompiledArtifactOptions {
    /** Creates managed configuration without resolving directories, Git or dependency configurations. */
    public CompiledArtifactOptions() {
        getRoots().convention(java.util.List.of()); getRequiredDirectories().convention(java.util.List.of());
        getRequiredFiles().convention(java.util.List.of()); getExpectedManifestSha256().convention("");
        var limits = CompiledArtifacts.Limits.defaults();
        getMaxFiles().convention(limits.maxFiles()); getMaxEntries().convention(limits.maxEntries());
        getMaxDepth().convention(limits.maxDepth()); getMaxFileBytes().convention(limits.maxFileBytes());
        getMaxTotalBytes().convention(limits.maxTotalBytes()); getMaxManifestBytes().convention(limits.maxManifestBytes());
    }
    /**
     * Exposes base directory against which all configured paths are interpreted.
     * @return base directory against which all configured paths are interpreted
     */
    public abstract DirectoryProperty getBaseDirectory();
    /**
     * Exposes manifest outside every inventoried tree.
     * @return manifest outside every inventoried tree
     */
    public abstract RegularFileProperty getManifestFile();
    /**
     * Exposes trusted full lowercase Git SHA-1/SHA-256, supplied independently of the manifest.
     * @return trusted full lowercase Git SHA-1/SHA-256, supplied independently of the manifest
     */
    public abstract Property<String> getRevision();
    /**
     * Exposes stable consumer suite identity.
     * @return stable consumer suite identity
     */
    public abstract Property<String> getSuite();
    /**
     * Exposes disjoint relative directories, all required to exist; no FileCollections or builtBy.
     * @return disjoint relative directories, all required to exist; no FileCollections or builtBy
     */
    public abstract ListProperty<String> getRoots();
    /**
     * Exposes directories that must contain a nonempty file.
     * @return directories that must contain a nonempty file
     */
    public abstract ListProperty<String> getRequiredDirectories();
    /**
     * Exposes files that must be nonempty.
     * @return files that must be nonempty
     */
    public abstract ListProperty<String> getRequiredFiles();
    /**
     * Exposes optional separately retained manifest SHA-256; never inferred from downloaded metadata.
     * @return optional separately retained manifest SHA-256; never inferred from downloaded metadata
     */
    public abstract Property<String> getExpectedManifestSha256();
    /**
     * Exposes maximum files.
     * @return maximum files
     */
    public abstract Property<Integer> getMaxFiles();
    /**
     * Exposes maximum visited entries, including empty directories.
     * @return maximum visited entries, including empty directories
     */
    public abstract Property<Integer> getMaxEntries();
    /**
     * Exposes maximum relative depth.
     * @return maximum relative depth
     */
    public abstract Property<Integer> getMaxDepth();
    /**
     * Exposes maximum streamed bytes per file.
     * @return maximum streamed bytes per file
     */
    public abstract Property<Long> getMaxFileBytes();
    /**
     * Exposes maximum streamed bytes across all files.
     * @return maximum streamed bytes across all files
     */
    public abstract Property<Long> getMaxTotalBytes();
    /**
     * Exposes maximum manifest bytes.
     * @return maximum manifest bytes
     */
    public abstract Property<Integer> getMaxManifestBytes();

    CompiledArtifacts.Inputs inputs() {
        return new CompiledArtifacts.Inputs(getBaseDirectory().get().getAsFile().toPath(), getRevision().get(), getSuite().get(),
                getRoots().get(), getRequiredDirectories().get(), getRequiredFiles().get(), new CompiledArtifacts.Limits(
                getMaxFiles().get(), getMaxEntries().get(), getMaxDepth().get(), getMaxFileBytes().get(), getMaxTotalBytes().get(), getMaxManifestBytes().get()));
    }

    CompiledArtifacts.Summary verify() {
        return CompiledArtifacts.verify(inputs(), getManifestFile().get().getAsFile().toPath(), getExpectedManifestSha256().get());
    }
}
