package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;

import static io.github.sjacek.shardpilot.ExecutionEvidence.Reason.*;

/** No extraction, traversal, symlink following or unbounded reads at the downloaded-artifact boundary. */
@NullMarked
final class EvidenceFiles {
    private EvidenceFiles() { }

    /** Reads one extra byte to enforce the cap even when a producer's declared length is false. */
    static byte[] read(Path file, long limit) {
        safe(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw problem(INVALID_EVIDENCE);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) throw problem(RESOURCE_LIMIT);
            return bytes;
        } catch (IOException failure) { throw problem(INVALID_EVIDENCE); }
    }

    static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) { throw problem(INVALID_EVIDENCE); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by Java", impossible); }
    }

    /** Checks every existing ancestor: NOFOLLOW_LINKS on the final file alone is insufficient. */
    static void safe(Path path) {
        Path absolute = path.toAbsolutePath();
        if (!absolute.normalize().equals(absolute)) throw problem(UNSAFE_ARTIFACT_PATH);
        Path part = absolute.getRoot();
        for (Path element : absolute) {
            part = part.resolve(element);
            if (Files.isSymbolicLink(part) || Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) {
                throw problem(UNSAFE_ARTIFACT_PATH);
            }
        }
    }

    static Path resolve(Path root, String relative, boolean xml) {
        String pattern = xml ? "(?:[A-Za-z0-9_.@$+-]+/)*TEST-[A-Za-z0-9_.@$+-]+\\.xml"
                : "(?:[A-Za-z0-9_.@$+-]+/)*[A-Za-z0-9_.@$+-]+\\.json";
        if (relative.length() > 512 || !relative.matches(pattern)) throw problem(UNSAFE_ARTIFACT_PATH);
        for (String segment : relative.split("/")) if (segment.equals(".") || segment.equals("..")) throw problem(UNSAFE_ARTIFACT_PATH);
        Path target = root.resolve(relative).toAbsolutePath();
        if (!target.normalize().equals(target) || !target.startsWith(root.toAbsolutePath())) throw problem(UNSAFE_ARTIFACT_PATH);
        safe(target);
        return target;
    }

    /** Scan without FOLLOW_LINKS, including ignored payloads, so undeclared XML/aliases cannot hide. */
    static Set<String> reports(Path root, ExecutionEvidence.Limits limits) {
        safe(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw problem(INVALID_EVIDENCE);
        var reports = new TreeSet<String>();
        var keys = new HashSet<>();
        var fallbackKeys = new java.util.HashMap<String, java.util.List<Path>>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                int entries;
                int comparisons;
                private void check(Path path, BasicFileAttributes attributes) throws IOException {
                    if (++entries > limits.maxReports() * 4L + 32 || root.relativize(path).getNameCount() > limits.maxDepth()) throw problem(RESOURCE_LIMIT);
                    if (attributes.isSymbolicLink() || attributes.isOther()) throw problem(UNSAFE_ARTIFACT_PATH);
                    Object key = attributes.fileKey();
                    if (attributes.isRegularFile() && key != null && !keys.add(key)) throw problem(UNSAFE_ARTIFACT_PATH);
                    // Windows providers may omit fileKey. Bound the fallback alias comparisons too;
                    // identical timestamps/lengths do not authorize quadratic filesystem work.
                    if (attributes.isRegularFile() && key == null) {
                        String metadata = attributes.size() + ":" + attributes.creationTime() + ":" + attributes.lastModifiedTime();
                        var candidates = fallbackKeys.computeIfAbsent(metadata, ignored -> new java.util.ArrayList<>());
                        for (Path candidate : candidates) {
                            if (++comparisons > Math.min(100_000L, limits.maxReports() * 8L)) throw problem(RESOURCE_LIMIT);
                            if (Files.isSameFile(path, candidate)) throw problem(UNSAFE_ARTIFACT_PATH);
                        }
                        candidates.add(path);
                    }
                }
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    check(path, attributes);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    check(path, attributes);
                    String name = path.getFileName().toString();
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                        if (!name.startsWith("TEST-") || !name.endsWith(".xml")) throw problem(INVALID_EVIDENCE);
                        if (reports.size() >= limits.maxReports()) throw problem(RESOURCE_LIMIT);
                        reports.add(root.relativize(path).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path path, IOException failure) { throw problem(INVALID_EVIDENCE); }
            });
        } catch (IOException failure) { throw problem(INVALID_EVIDENCE); }
        return reports;
    }

    /**
     * Proves disjoint ownership before the writer invalidates any old output. Canonical existing
     * ancestors cover not-yet-created paths; isSameFile additionally covers hard-linked aliases.
     */
    static void protect(ExecutionEvidence.Inputs inputs, Path json, Path html) throws IOException {
        Path first = canonical(json);
        Path second = canonical(html);
        if (first.startsWith(second) || second.startsWith(first) || same(json, html)) throw new IllegalArgumentException("C2 reports require separate output files");
        for (Path output : new Path[] {json, html}) {
            safe(output);
            if (Files.exists(output) && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("C2 outputs must be regular files");
            Path target = canonical(output);
            Path nodes = canonical(inputs.nodesDirectory());
            if (target.startsWith(nodes) || nodes.startsWith(target)) throw new IllegalArgumentException("Keep C2 reports outside retained inputs");
            for (Path input : new Path[] {inputs.expectedRequest(), inputs.eligibility(), inputs.selection()}) {
                Path source = canonical(input);
                if (source.startsWith(target) || target.equals(source) || same(output, input)) throw new IllegalArgumentException("C2 reports must not overwrite retained inputs");
            }
            // Hard links can alias input bytes despite disjoint directories.
            if (Files.exists(output) && Files.isDirectory(inputs.nodesDirectory())) {
                try (var paths = Files.walk(inputs.nodesDirectory(), inputs.limits().maxDepth() + 2)) {
                    var iterator = paths.iterator();
                    long visited = 0;
                    while (iterator.hasNext()) {
                        if (++visited > inputs.limits().maxNodes() * (inputs.limits().maxReports() * 4L + 32)) throw problem(RESOURCE_LIMIT);
                        Path input = iterator.next();
                        if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && same(output, input)) throw new IllegalArgumentException("C2 reports must not alias retained inputs");
                    }
                }
            }
        }
    }

    private static boolean same(Path first, Path second) throws IOException {
        return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
    }

    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute;
        while (!Files.exists(parent)) parent = parent.getParent();
        return parent.toRealPath().resolve(parent.relativize(absolute)).normalize();
    }

    static Problem problem(ExecutionEvidence.Reason reason) { return new Problem(reason); }

    /** Carries only a fixed code: never chain parser or filesystem exceptions with private payloads. */
    static final class Problem extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        final ExecutionEvidence.Reason reason;
        Problem(ExecutionEvidence.Reason reason) { super("C2 " + reason); this.reason = reason; }
    }
}
