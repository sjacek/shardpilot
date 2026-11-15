package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Private C4 storage: fixed filenames, bounded JSON and no diagnostic payloads in exceptions. */
final class DiagnosticsFiles {
    private DiagnosticsFiles() { }

    /** Rejects known alias paths, including Windows reparse entries; not a sandbox against concurrent writers. */
    static void safe(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.normalize().equals(absolute)) throw Diagnostics.problem("UNSAFE_PATH");
        Path part = absolute.getRoot();
        for (Path element : absolute) {
            part = part.resolve(element);
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw Diagnostics.problem("UNSAFE_PATH");
            }
        }
    }

    static byte[] read(Path path, int limit) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw Diagnostics.problem("MISSING_DOCUMENT");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw Diagnostics.problem("RESOURCE_LIMIT");
            return bytes;
        }
    }

    static Map<?, ?> json(Path path) throws IOException {
        try {
            Object value = EvidenceJson.parse(EvidenceFiles.text(read(path, 65_536)), 10_000);
            if (value instanceof Map<?, ?> map) return map;
        } catch (IllegalArgumentException invalid) { throw Diagnostics.problem("INVALID_DOCUMENT"); }
        throw Diagnostics.problem("INVALID_DOCUMENT");
    }

    /** Replaces one directory entry; callers decide report-pair ordering and own the directory exclusively. */
    static void write(Path path, String text) throws IOException {
        safe(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw Diagnostics.problem("UNSAFE_PATH");
        Files.createDirectories(path.toAbsolutePath().getParent());
        safe(path.getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".c4-", ".tmp");
        try {
            Files.writeString(temporary, text, java.nio.charset.StandardCharsets.UTF_8);
            // Replace the directory entry, never truncate a potentially hard-linked input in place.
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
