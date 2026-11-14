package io.github.sjacek.shardpilot;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Validate transport arguments before a checkout or a subprocess can have any side effects. */
class GitChangeOptionsTest {
    @ParameterizedTest
    @CsvSource({"https://example.invalid/repo,256,30", "--upload-pack=bad,256,30", "origin,0,30",
            "origin,-1,30", "origin,100001,30", "origin,256,0", "origin,256,-1", "origin,256,301"})
    void rejectsRemoteUrlsOptionsAndUnboundedResources(String remote, int depth, int seconds) {
        assertThrows(IllegalArgumentException.class, () -> new GitChangeOptions(Path.of("."), "main", "HEAD", true, true, remote, depth, seconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--all", "main..HEAD", "main:path", "main branch", "main\\other", "main/", "main//other"})
    void validatesTheBaseAsWellAsTheHead(String base) {
        assertThrows(IllegalArgumentException.class, () -> new GitChangeOptions(Path.of("."), base, "HEAD", true, false, "origin", 256, 30));
    }

    @Test
    void defaultsStayOfflineAndNormalizeTheCheckoutWithoutAccessingIt() {
        var options = GitChangeOptions.defaults(Path.of("missing-testdata", "..", "checkout-testdata"));
        assertEquals(Path.of("checkout-testdata").toAbsolutePath(), options.directory());
        assertEquals("", options.base());
        assertEquals("HEAD", options.head());
        assertTrue(options.mergeBase());
        assertFalse(options.fetch());
        assertEquals("origin", options.remote());
        assertEquals(256, options.fetchDepth());
        assertEquals(30, options.timeoutSeconds());
        assertDoesNotThrow(() -> new GitChangeOptions(Path.of("."), "refs/heads/feature-testdata", "HEAD~2^0", false, true, "origin", 100000, 300));
        assertThrows(IllegalArgumentException.class, () -> new GitChangeOptions(Path.of("."), "a".repeat(257), "HEAD", true, false, "origin", 256, 30));
    }
}
