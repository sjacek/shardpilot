package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Downloaded metadata must be rejected as corrupt, never reinterpreted as a safe full fallback. */
class ImpactSnapshotsTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("invalidFields")
    void rejectsMalformedObservationWithoutRetainingInputOrParserCause(String field, @Nullable Object value) throws Exception {
        var observation = observation();
        observation.put(field, value);
        assertCorrupt(snapshot(Map.of("contracts/api.json", observation)));
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("format", "PRIVATE_FORMAT"),
                Arguments.of("failure", "PRIVATE_FAILURE"),
                Arguments.of("beforeSha256", "PRIVATE_HASH"),
                Arguments.of("afterSha256", "A".repeat(64)),
                Arguments.of("pointers", null),
                Arguments.of("pointers", "/api"),
                Arguments.of("pointers", List.of(1)),
                Arguments.of("pointers", List.of("/api", "/api")),
                Arguments.of("pointers", List.of("/bad~2")),
                Arguments.of("pointers", List.of("PRIVATE_RELATIVE_POINTER")),
                Arguments.of("failure", "READ_FAILED"), // A failed observation cannot carry provisional pointers.
                Arguments.of("PRIVATE_EXTRA", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "failure", "pointers", "beforeSha256", "afterSha256"})
    void rejectsMissingFieldsEvenWhenFallbackWouldBeFull(String field) throws Exception {
        var observation = observation();
        observation.remove(field);
        assertCorrupt(snapshot(Map.of("contracts/api.json", observation)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contracts/foreign.json", "../PRIVATE_ESCAPE", "/PRIVATE_ABSOLUTE"})
    void observationsMustReferToNormalizedPathsInTheCompleteChangedSet(String path) throws Exception {
        assertCorrupt(snapshot(Map.of(path, observation())));
    }

    @Test
    void aCompleteComparisonNeedsAtLeastOneRawBlobIdentity() throws Exception {
        var observation = observation();
        observation.put("beforeSha256", "");
        observation.put("afterSha256", "");
        assertCorrupt(snapshot(Map.of("contracts/api.json", observation)));
    }

    @Test
    void semanticNoOpAndExplicitUnreadableObservationRemainDistinctAcrossRoundTrip() throws Exception {
        for (String failure : List.of("NONE", "READ_FAILED", "RESOURCE_LIMIT")) {
            var observation = observation();
            observation.put("failure", failure);
            observation.put("pointers", List.of());
            if (!failure.equals("NONE")) {
                observation.put("beforeSha256", "");
                observation.put("afterSha256", "");
            }
            Path file = directory.resolve("git.json");
            Files.writeString(file, snapshot(Map.of("contracts/api.json", observation)));
            var result = GitChanges.read(file);
            Files.writeString(file, result.json());
            assertEquals(result, GitChanges.read(file));
            var restored = result.changes().contracts().get("contracts/api.json");
            assertNotNull(restored);
            assertEquals(failure, restored.failure().name());
            assertTrue(restored.pointers().isEmpty());
        }
    }

    private void assertCorrupt(String json) throws Exception {
        Path file = directory.resolve("git.json");
        Files.writeString(file, json);
        var error = assertThrows(IllegalArgumentException.class, () -> GitChanges.read(file));
        assertFalse(error.getMessage().contains("PRIVATE"));
        assertNull(error.getCause());
    }

    private static Map<String, Object> observation() {
        return new TreeMap<>(Map.of("format", "JSON", "failure", "NONE", "pointers", List.of("/api"),
                "beforeSha256", "a".repeat(64), "afterSha256", "b".repeat(64)));
    }

    private static String snapshot(Map<String, ?> observations) {
        return EvidenceJson.write(Map.of("schemaVersion", 2, "source", "EXPLICIT", "failure", "NONE",
                "availability", "KNOWN", "baseRevision", "a".repeat(40), "headRevision", "b".repeat(40),
                "paths", List.of("contracts/api.json"), "contracts", observations));
    }
}
