package io.github.sjacek.shardpilot;

import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * Build-tool-independent Git acquisition parameters. No command is run during construction.
 * @param directory checkout or a directory inside it
 * @param base explicit base revision, or empty for supported CI inference
 * @param head revision resolving to the checked-out HEAD
 * @param mergeBase compare against the common ancestor rather than directly against base
 * @param fetch explicitly permit bounded acquisition of missing history
 * @param remote configured remote name, never a URL
 * @param fetchDepth maximum requested fetch depth
 * @param timeoutSeconds timeout for each Git command
 */
@NullMarked
public record GitChangeOptions(Path directory, String base, String head, boolean mergeBase,
                               boolean fetch, String remote, int fetchDepth, int timeoutSeconds) {
    /** Validates revision/remote syntax and resource bounds before starting any process. */
    public GitChangeOptions {
        directory = directory.toAbsolutePath().normalize();
        if (!base.isEmpty()) validateRevision(base);
        validateRevision(head);
        if (!remote.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                || fetchDepth < 1 || fetchDepth > 100_000 || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Invalid Git acquisition parameters");
        }
    }

    /**
     * Creates offline-by-default acquisition with CI inference and merge-base comparison.
     * @param directory checkout directory
     * @return HEAD, origin, depth 256 and a 30-second command timeout, without fetching
     */
    public static GitChangeOptions defaults(Path directory) {
        return new GitChangeOptions(directory, "", "HEAD", true, false, "origin", 256, 30);
    }

    static void validateRevision(String value) {
        if (value.length() > 256 || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*(?:[~^][0-9]*)*")
                || value.contains("..") || value.contains("//") || value.endsWith("/")) {
            throw new IllegalArgumentException("Invalid Git revision parameter");
        }
    }
}
