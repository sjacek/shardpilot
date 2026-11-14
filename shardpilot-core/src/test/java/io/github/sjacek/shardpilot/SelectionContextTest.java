package io.github.sjacek.shardpilot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.SelectionContext.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionContextTest {
    @Test
    void freezesEligibilityHistoryRequestsAndChangePaths() {
        var names = new HashSet<>(Set.of("example.Test"));
        var paths = new HashSet<>(Set.of("src/space in name/Żółć.java"));
        var history = new HashMap<>(Map.of("example.Test", 1d));
        var changes = ChangeSet.known(paths);
        var context = new SelectionContext("daily-testdata", names, changes, history, names);
        names.clear();
        paths.clear();
        history.clear();
        assertEquals(Set.of("example.Test"), context.eligible());
        assertEquals(context.eligible(), context.explicitClasses());
        assertEquals(Map.of("example.Test", 1d), context.history());
        assertEquals(Set.of("src/space in name/Żółć.java"), context.changes().paths());
        assertThrows(UnsupportedOperationException.class, () -> context.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.eligible().clear());
        assertThrows(UnsupportedOperationException.class, () -> changes.paths().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/src/a", "C:/src/a", "src\\a", "src//a", "./src/a", "src/../a", "src/", "src/\na"})
    void rejectsUnnormalizedPathsInsteadOfReinterpretingThem(String path) {
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.known(Set.of(path)));
    }

    @Test
    void validatesRevisionProvenanceAndUnavailableAcquisition() {
        String sha1 = "a".repeat(40);
        String sha256 = "b".repeat(64);
        assertEquals(sha256, new ChangeSet(Availability.KNOWN, Set.of(), sha1, sha256).headRevision());
        assertEquals(sha1, new ChangeSet(Availability.UNKNOWN, Set.of(), "", sha1).headRevision());
        assertThrows(IllegalArgumentException.class, () -> new ChangeSet(Availability.UNKNOWN, Set.of("a"), "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ChangeSet(Availability.KNOWN, Set.of(), "", sha1));
        assertThrows(IllegalArgumentException.class, () -> new ChangeSet(Availability.KNOWN, Set.of(), "main", "HEAD"));
        assertThrows(IllegalArgumentException.class, () -> new ChangeSet(Availability.KNOWN, Set.of(), sha1.toUpperCase(java.util.Locale.ROOT), sha1));
    }

    @Test
    void validatesAllHistoryEvenForFamiliesOutsideEligibility() {
        for (double cost : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new SelectionContext("full-testdata", Set.of("example.Test"),
                    ChangeSet.unknown(), Map.of("unrelated.Other", cost), Set.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new SelectionContext("full-testdata", Set.of("a/b"),
                ChangeSet.unknown(), Map.of(), Set.of()));
    }

    @Test
    void rejectsUnpairedSurrogatesThatWouldCollideDuringUtf8IdentityEncoding() {
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.known(Set.of("src/" + (char) 0xD800 + ".java")));
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.known(Set.of("src/" + (char) 0xDC00 + ".java")));
    }
}
