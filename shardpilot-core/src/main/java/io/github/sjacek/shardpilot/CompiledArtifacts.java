package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * C3's portable, offline compilation-artifact boundary. Sealing and verification use the same
 * bounded inventory; neither operation compiles, extracts archives, executes Git or resolves dependencies.
 * Expected revision, suite and paths are trusted consumer configuration, not values copied from a manifest.
 */
@NullMarked
public final class CompiledArtifacts {
    private CompiledArtifacts() { }

    /**
     * Resource ceilings apply to actual streamed bytes and visited entries, including empty directories.
     * @param maxFiles maximum regular files
     * @param maxEntries maximum files and directories
     * @param maxDepth maximum depth relative to the base directory
     * @param maxFileBytes maximum streamed bytes in one file
     * @param maxTotalBytes maximum streamed bytes across all files
     * @param maxManifestBytes maximum retained JSON bytes
     */
    public record Limits(int maxFiles, int maxEntries, int maxDepth, long maxFileBytes, long maxTotalBytes, int maxManifestBytes) {
        /** Reject nonpositive limits and any attempt to raise the distribution's hard ceilings. */
        public Limits {
            if (maxFiles < 1 || maxFiles > 100_000 || maxEntries < 1 || maxEntries > 200_000 || maxDepth < 1 || maxDepth > 64
                    || maxFileBytes < 1 || maxFileBytes > 1_073_741_824L || maxTotalBytes < 1 || maxTotalBytes > 8_589_934_592L
                    || maxManifestBytes < 1 || maxManifestBytes > 33_554_432) throw problem("INVALID_LIMITS");
        }
        /**
         * Supplies ceilings suitable for large compiled suites without permitting unbounded input.
         * @return default ceilings
         */
        public static Limits defaults() { return new Limits(100_000, 200_000, 64, 1_073_741_824L, 8_589_934_592L, 33_554_432); }
    }

    /**
     * Root-relative directory trees to seal. Roots must exist and must not overlap. Required directories
     * contain at least one nonempty file; required files are nonempty and belong to a declared root.
     * Lists are copied and sorted so Gradle and Maven produce identical bytes for identical inputs.
     * @param directory existing artifact base directory
     * @param revision trusted full lowercase Git object ID
     * @param suite consumer suite identity
     * @param roots disjoint existing directory trees, relative to the base directory
     * @param requiredDirectories directories requiring a nonempty file
     * @param requiredFiles files requiring nonempty bytes
     * @param limits resource ceilings
     */
    public record Inputs(Path directory, String revision, String suite, List<String> roots,
                         List<String> requiredDirectories, List<String> requiredFiles, Limits limits) {
        /** Validate the consumer contract before any filesystem mutation. */
        public Inputs {
            if (!revision.matches("[a-f0-9]{40}|[a-f0-9]{64}") || !suite.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw problem("INVALID_IDENTITY");
            directory = directory.toAbsolutePath().normalize();
            roots = paths(roots); requiredDirectories = paths(requiredDirectories); requiredFiles = paths(requiredFiles);
            if (roots.isEmpty() || roots.size() > 256 || roots.size() > limits.maxEntries()
                    || requiredDirectories.size() > limits.maxEntries() || requiredFiles.size() > limits.maxFiles()) throw problem("INVALID_ROOTS");
            for (int a = 0; a < roots.size(); a++) for (int b = a + 1; b < roots.size(); b++) {
                String first = roots.get(a).toLowerCase(java.util.Locale.ROOT), second = roots.get(b).toLowerCase(java.util.Locale.ROOT);
                if (within(first, second) || within(second, first)) throw problem("OVERLAPPING_ROOTS");
            }
            for (String path : requiredDirectories) if (roots.stream().noneMatch(root -> within(path, root))) throw problem("REQUIREMENT_OUTSIDE_ROOTS");
            for (String path : requiredFiles) if (roots.stream().noneMatch(root -> within(path, root) && !path.equals(root))) throw problem("REQUIREMENT_OUTSIDE_ROOTS");
        }
    }

    /**
     * Summary contains metadata only; hashes do not authenticate a compromised producer.
     * @param files regular file count
     * @param bytes total streamed bytes
     * @param manifestSha256 SHA-256 of the exact retained JSON bytes
     */
    public record Summary(int files, long bytes, String manifestSha256) { }

    /**
     * Seal a complete inventory. The output is disjoint from all roots, replaced atomically when supported,
     * and an earlier success is removed before scanning so a failed reseal cannot retain a stale manifest.
     * @param inputs trusted compilation cohort
     * @param manifest JSON destination outside the artifact trees
     * @return counts and digest suitable for retaining separately in trusted CI metadata
     * @throws IOException if the destination cannot be written
     */
    public static Summary seal(Inputs inputs, Path manifest) throws IOException {
        // Validate output ownership before invalidation: an unsafe destination must not cause
        // deletion of an input, a directory or a symlink supplied in place of an old manifest.
        Path target = output(inputs, manifest);
        Files.deleteIfExists(target);
        var inventory = scan(inputs);
        // One root object plus eight field values; each file contributes its object and two values.
        // Keep this producer budget aligned with the reader when extending the versioned document.
        if (9L + inputs.roots().size() + inputs.requiredDirectories().size() + inputs.requiredFiles().size()
                + inventory.directories.size() + inventory.files.size() * 3L > 500_000) throw problem("RESOURCE_LIMIT");
        byte[] bytes = EvidenceJson.write(document(inputs, inventory)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > inputs.limits().maxManifestBytes()) throw problem("RESOURCE_LIMIT");
        Files.createDirectories(target.getParent());
        safe(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".shardpilot-c3-", ".tmp");
        try {
            Files.write(temporary, bytes);
            safe(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return new Summary(inventory.files.size(), inventory.bytes, EvidenceFiles.digest(bytes));
    }

    /**
     * Re-read the manifest and every byte on every invocation. The optional digest must be supplied
     * independently (for example by trusted CI metadata); a hash downloaded alongside a mutable manifest
     * is not authentication. No operation repairs missing artifacts or creates a verification success file.
     * @param inputs independently supplied expected cohort and path requirements
     * @param manifest retained compilation manifest
     * @param expectedManifestSha256 independently retained SHA-256, or empty for a trusted artifact channel
     * @return validated counts and manifest digest
     */
    public static Summary verify(Inputs inputs, Path manifest, String expectedManifestSha256) {
        output(inputs, manifest);
        if (!expectedManifestSha256.isEmpty() && !expectedManifestSha256.matches("[a-f0-9]{64}")) throw problem("INVALID_MANIFEST_DIGEST");
        byte[] bytes;
        try { bytes = EvidenceFiles.read(manifest, inputs.limits().maxManifestBytes()); }
        catch (IllegalArgumentException failure) { throw problem("INVALID_MANIFEST"); }
        String digest = EvidenceFiles.digest(bytes);
        if (!expectedManifestSha256.isEmpty() && !digest.equals(expectedManifestSha256)) throw problem("MANIFEST_DIGEST_MISMATCH");
        Map<?, ?> expected;
        try {
            Object parsed = EvidenceJson.parse(EvidenceFiles.text(bytes), 500_000);
            if (!(parsed instanceof Map<?, ?> map)) throw problem("INVALID_MANIFEST");
            expected = map;
        } catch (IllegalArgumentException failure) { throw problem("INVALID_MANIFEST"); }
        if (!expected.keySet().equals(Set.of("schemaVersion", "revision", "suite", "roots", "requiredDirectories", "requiredFiles", "directories", "files"))
                || !(expected.get("schemaVersion") instanceof BigDecimal version) || !version.equals(BigDecimal.ONE)) throw problem("INVALID_MANIFEST");
        if (!inputs.revision().equals(expected.get("revision"))) throw problem("REVISION_MISMATCH");
        if (!inputs.suite().equals(expected.get("suite"))) throw problem("SUITE_MISMATCH");
        // Requirements are independent consumer inputs. Do not weaken them by adopting a
        // producer's root list or required paths, even if that would make its inventory pass.
        if (!inputs.roots().equals(expected.get("roots")) || !inputs.requiredDirectories().equals(expected.get("requiredDirectories"))
                || !inputs.requiredFiles().equals(expected.get("requiredFiles"))) throw problem("POLICY_MISMATCH");
        // Enumerate trusted configured roots rather than resolving producer-supplied file keys.
        // The base path is deliberately absent from the manifest so workers can relocate the cohort.
        var inventory = scan(inputs);
        var actual = document(inputs, inventory);
        if (!(expected.get("files") instanceof Map<?, ?> files) || !(expected.get("directories") instanceof List<?> directories)
                || files.size() > inputs.limits().maxFiles() || directories.size() > inputs.limits().maxEntries()) throw problem("INVALID_MANIFEST");
        // Comparing the complete key set rejects stale extra bytecode/resources as well as missing files.
        if (!files.keySet().equals(inventory.files.keySet())) throw problem("FILE_SET_MISMATCH");
        if (!directories.equals(actual.get("directories"))) throw problem("DIRECTORY_SET_MISMATCH");
        if (!files.equals(actual.get("files"))) throw problem("FILE_CONTENT_MISMATCH");
        return new Summary(inventory.files.size(), inventory.bytes, digest);
    }

    private record Inventory(Map<String, Object> files, Set<String> directories, long bytes) { }

    /** Walk without following links and hash streams; payload bytes never enter diagnostics. */
    private static Inventory scan(Inputs inputs) {
        var files = new TreeMap<String, Object>();
        var directories = new TreeSet<String>();
        var aliases = new HashSet<>();
        var portableNames = new HashSet<String>();
        var withoutKeys = new HashMap<String, List<Path>>();
        long[] total = {0}; int[] entries = {0}; int[] comparisons = {0};
        safe(inputs.directory());
        try {
            for (String root : inputs.roots()) {
                Path tree = inputs.directory().resolve(root);
                safe(tree);
                if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) throw problem("REQUIRED_ARTIFACT_MISSING");
                Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                    private String check(Path path, BasicFileAttributes attributes) throws IOException {
                        String relative = inputs.directory().relativize(path).toString().replace('\\', '/');
                        validatePath(relative);
                        if (++entries[0] > inputs.limits().maxEntries() || inputs.directory().relativize(path).getNameCount() > inputs.limits().maxDepth()) throw problem("RESOURCE_LIMIT");
                        if (attributes.isSymbolicLink() || attributes.isOther()) throw problem("UNSAFE_ARTIFACT_PATH");
                        if (!portableNames.add(relative.toLowerCase(java.util.Locale.ROOT))) throw problem("AMBIGUOUS_ARTIFACT_PATH");
                        if (attributes.isRegularFile()) {
                            Object key = attributes.fileKey();
                            if (key != null && !aliases.add(key)) throw problem("UNSAFE_ARTIFACT_ALIAS");
                            if (key == null) {
                                // Metadata only groups candidates; it is never evidence of identity.
                                // isSameFile performs the check, with a cap on fallback comparisons.
                                String metadata = attributes.size() + ":" + attributes.creationTime() + ":" + attributes.lastModifiedTime();
                                var candidates = withoutKeys.computeIfAbsent(metadata, ignored -> new ArrayList<>());
                                for (Path other : candidates) {
                                    if (++comparisons[0] > 100_000) throw problem("RESOURCE_LIMIT");
                                    if (Files.isSameFile(path, other)) throw problem("UNSAFE_ARTIFACT_ALIAS");
                                }
                                candidates.add(path);
                            }
                        }
                        return relative;
                    }
                    @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                        directories.add(check(path, attributes)); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                        String name = check(path, attributes);
                        if (!attributes.isRegularFile()) throw problem("UNSAFE_ARTIFACT_PATH");
                        if (files.size() >= inputs.limits().maxFiles() || attributes.size() > inputs.limits().maxFileBytes()) throw problem("RESOURCE_LIMIT");
                        MessageDigest hash = sha256(); long length = 0;
                        safe(path);
                        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] buffer = new byte[65_536]; int read;
                            while ((read = stream.read(buffer)) != -1) {
                                length += read; total[0] += read;
                                if (length > inputs.limits().maxFileBytes() || total[0] > inputs.limits().maxTotalBytes()) throw problem("RESOURCE_LIMIT");
                                hash.update(buffer, 0, read);
                            }
                        }
                        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        // Detect observable concurrent writes/replacements. This is not a filesystem
                        // snapshot: exclusive producer/worker ownership is still a precondition.
                        if (!after.isRegularFile() || length != attributes.size() || after.size() != attributes.size()
                                || !after.lastModifiedTime().equals(attributes.lastModifiedTime()) || !java.util.Objects.equals(after.fileKey(), attributes.fileKey())) throw problem("ARTIFACT_CHANGED_DURING_READ");
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("bytes", BigDecimal.valueOf(length)); entry.put("sha256", HexFormat.of().formatHex(hash.digest())); files.put(name, entry);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path path, IOException failure) { throw problem("UNREADABLE_ARTIFACT"); }
                });
            }
        } catch (IOException failure) { throw problem("UNREADABLE_ARTIFACT"); }
        if (files.isEmpty()) throw problem("REQUIRED_ARTIFACT_MISSING");
        for (String name : inputs.requiredFiles()) if (!nonempty(files.get(name))) throw problem("REQUIRED_ARTIFACT_MISSING");
        // Index ancestors once rather than performing requirements × files filesystem work.
        var nonemptyDirectories = new HashSet<String>();
        for (var entry : files.entrySet()) if (nonempty(entry.getValue())) {
            String parent = entry.getKey(); int slash;
            while ((slash = parent.lastIndexOf('/')) >= 0) { parent = parent.substring(0, slash); nonemptyDirectories.add(parent); }
        }
        for (String name : inputs.requiredDirectories()) if (!directories.contains(name) || !nonemptyDirectories.contains(name)) throw problem("REQUIRED_ARTIFACT_MISSING");
        return new Inventory(files, directories, total[0]);
    }

    private static boolean nonempty(@Nullable Object value) {
        return value instanceof Map<?, ?> map && map.get("bytes") instanceof BigDecimal length && length.signum() > 0;
    }

    private static Map<String, Object> document(Inputs inputs, Inventory inventory) {
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", 1); document.put("revision", inputs.revision()); document.put("suite", inputs.suite());
        document.put("roots", inputs.roots()); document.put("requiredDirectories", inputs.requiredDirectories()); document.put("requiredFiles", inputs.requiredFiles());
        document.put("directories", List.copyOf(inventory.directories)); document.put("files", inventory.files);
        return document;
    }

    private static List<String> paths(List<String> values) {
        var paths = new TreeSet<String>(); var folded = new HashSet<String>();
        for (String value : values) {
            validatePath(value);
            if (!paths.add(value) || !folded.add(value.toLowerCase(java.util.Locale.ROOT))) throw problem("AMBIGUOUS_ARTIFACT_PATH");
        }
        return List.copyOf(paths);
    }

    private static void validatePath(String value) {
        if (value.isEmpty() || value.length() > 1024 || value.startsWith("/")
                || value.chars().anyMatch(character -> Character.isISOControl(character) || "\\:<>\"|?*".indexOf(character) >= 0)) throw problem("UNSAFE_ARTIFACT_PATH");
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw problem("UNSAFE_ARTIFACT_PATH");
        }
    }

    private static boolean within(String path, String root) { return path.equals(root) || path.startsWith(root + "/"); }

    private static Path output(Inputs inputs, Path manifest) {
        // Use the caller's manifest destination, not any path contained in the downloaded JSON.
        Path absolute = manifest.toAbsolutePath().normalize(); safe(absolute);
        for (String root : inputs.roots()) {
            Path tree = inputs.directory().resolve(root);
            if (absolute.startsWith(tree) || tree.startsWith(absolute)) throw problem("MANIFEST_ALIASES_ARTIFACTS");
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw problem("UNSAFE_MANIFEST_PATH");
        return absolute;
    }

    private static void safe(Path path) {
        Path absolute = path.toAbsolutePath();
        if (!absolute.normalize().equals(absolute)) throw problem("UNSAFE_ARTIFACT_PATH");
        Path component = absolute.getRoot();
        try {
            for (Path element : absolute) {
                component = component.resolve(element);
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (java.nio.file.NoSuchFileException missing) { continue; }
                // Windows directory junctions can be isOther() while still being directories.
                // Check ancestors too, not just the final file or the walk's starting directory.
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) throw problem("UNSAFE_ARTIFACT_PATH");
            }
        } catch (IOException failure) { throw problem("UNSAFE_ARTIFACT_PATH"); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by Java", impossible); }
    }

    private static Problem problem(String code) { return new Problem(code); }

    /** Fixed reason codes omit filesystem paths, parser excerpts and test/resource payloads. */
    public static final class Problem extends IllegalArgumentException {
        @Serial private static final long serialVersionUID = 1L;
        /** Stable classification with no private payload. */
        private final String code;
        private Problem(String code) { super("C3 " + code); this.code = code; }
        /**
         * Classifies the rejected input without quoting its content.
         * @return stable diagnostic reason
         */
        public String code() { return code; }
    }
}
