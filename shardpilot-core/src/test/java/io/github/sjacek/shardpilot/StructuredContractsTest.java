package io.github.sjacek.shardpilot;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Structural changes must be complete, lossless and free of document payloads in retained evidence. */
class StructuredContractsTest {
    @ParameterizedTest
    @EnumSource(SelectionImpact.Format.class)
    void detectsOnlyChangedSubtreesAndEscapesPointerSegments(SelectionImpact.Format format) {
        String before = format == SelectionImpact.Format.JSON ? "{\"a/b~c\":{\"x\":1},\"stable\":true}" : "a/b~c: {x: 1}\nstable: true\n";
        String after = format == SelectionImpact.Format.JSON ? "{\"stable\":true,\"a/b~c\":{\"x\":2}}" : "stable: true\na/b~c: {x: 2}\n";
        var result = compare(format, before, after);
        assertEquals(SelectionImpact.Failure.NONE, result.failure()); assertEquals(Set.of("/a~1b~0c/x"), result.pointers());
        assertNotEquals(result.beforeSha256(), result.afterSha256());
    }

    @ParameterizedTest
    @EnumSource(SelectionImpact.Format.class)
    void numericPrecisionIsNotRoundedAwayAndFormattingIsNotAnImpact(SelectionImpact.Format format) {
        assertEquals(Set.of("/n"), compare(format, "{\"n\":0.10000000000000001}", "{\"n\":0.1}").pointers());
        assertTrue(compare(format, "{\"n\":1}", "{\"n\":1.0}").pointers().isEmpty());
        if (format == SelectionImpact.Format.YAML) assertTrue(compare(format, "a: 1\nb: true\n", "# formatting\nb: true\na: 1.00\n").pointers().isEmpty());
    }

    @Test
    void arraysAreAtomicAndRemovingAParentIntersectsWatchedChildren() {
        assertEquals(Set.of("/items"), compare(SelectionImpact.Format.JSON, "{\"items\":[1,2]}", "{\"items\":[2,1]}").pointers());
        assertEquals(Set.of("/a"), compare(SelectionImpact.Format.JSON, "{\"a\":{\"b\":1}}", "{}").pointers());
        assertTrue(StructuredContracts.intersects("/a/b", "/a")); assertTrue(StructuredContracts.intersects("/a", "/a/b"));
        assertFalse(StructuredContracts.intersects("/a", "/ab"));
    }

    @Test
    void absenceIsDifferentFromANullDocumentOrProperty() {
        assertEquals(Set.of(""), StructuredContracts.compare(SelectionImpact.Format.JSON, null, bytes("null")).pointers());
        assertEquals(Set.of(""), StructuredContracts.compare(SelectionImpact.Format.YAML, bytes("null"), null).pointers());
        assertEquals(Set.of("/a"), compare(SelectionImpact.Format.JSON, "{}", "{\"a\":null}").pointers());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "a: 1\na: 2", "x: &ref [1]\ny: *ref", "x: !!java/object PRIVATE", "? [a,b]\n: c", "n: .nan", "n: .inf"})
    void unsafeYamlOrDuplicateDataIsUnknownWithoutEchoingValues(String text) {
        var result = compare(SelectionImpact.Format.YAML, "{}", text);
        assertEquals(SelectionImpact.Failure.INVALID_DOCUMENT, result.failure()); assertTrue(result.pointers().isEmpty());
        assertFalse(ImpactSnapshots.json(java.util.Map.of("contracts/example.yaml", result)).contains("PRIVATE"));
    }

    @Test
    void boundsBytesDepthAndRetainedPointerCountAndRejectsMalformedUtf8() {
        assertEquals(SelectionImpact.Failure.RESOURCE_LIMIT, StructuredContracts.compare(SelectionImpact.Format.JSON, bytes("{}"), new byte[1_048_577]).failure());
        assertNotEquals(SelectionImpact.Failure.NONE, compare(SelectionImpact.Format.JSON, "{}", "[".repeat(40) + "0" + "]".repeat(40)).failure());
        assertEquals(SelectionImpact.Failure.INVALID_DOCUMENT, StructuredContracts.compare(SelectionImpact.Format.JSON, bytes("{}"), new byte[] {(byte) 0xc3, 0x28}).failure());
        String many = "{" + String.join(",", java.util.stream.IntStream.range(0,4097).mapToObj(i -> "\"k" + i + "\":1").toList()) + "}";
        var result = compare(SelectionImpact.Format.JSON, "{}", many);
        assertEquals(SelectionImpact.Failure.RESOURCE_LIMIT, result.failure()); assertTrue(result.pointers().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(SelectionImpact.Format.class)
    void acceptsTheExactByteAndDepthBoundariesWithoutDroppingPointers(SelectionImpact.Format format) {
        var byteBoundary = compare(format, "{}", "{}" + " ".repeat(1_048_574));
        assertEquals(SelectionImpact.Failure.NONE, byteBoundary.failure());
        assertTrue(byteBoundary.pointers().isEmpty());
        String deepest = "{\"k\":".repeat(32) + "0" + "}".repeat(32);
        var depthBoundary = compare(format, "{}", deepest);
        assertEquals(SelectionImpact.Failure.NONE, depthBoundary.failure());
        assertEquals(Set.of("/k"), depthBoundary.pointers());
    }

    @Test
    void theLastAllowedPointerIsCompleteButTheNextOneNeverProducesAPartialSuccess() {
        String fields = String.join(",", java.util.stream.IntStream.range(0,4096).mapToObj(i -> "\"k" + i + "\":1").toList());
        var allowed = compare(SelectionImpact.Format.JSON, "{}", "{" + fields + "}");
        assertEquals(SelectionImpact.Failure.NONE, allowed.failure());
        assertEquals(4096, allowed.pointers().size());
        var rejected = compare(SelectionImpact.Format.JSON, "{}", "{" + fields + ",\"last\":1}");
        assertEquals(SelectionImpact.Failure.RESOURCE_LIMIT, rejected.failure());
        assertTrue(rejected.pointers().isEmpty());
        assertFalse(rejected.beforeSha256().isEmpty());
        assertFalse(rejected.afterSha256().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{} PRIVATE_TRAILING", "{\"a\":1,\"a\":2}", "[1,]", "{\"a\":NaN}", "{\"a\":01}", "{\"a\":\"\\uD800\"}"})
    void malformedJsonIsUnknownAndNeverSerializesParserPayloads(String text) {
        var result = compare(SelectionImpact.Format.JSON, "{}", text);
        assertEquals(SelectionImpact.Failure.INVALID_DOCUMENT, result.failure());
        assertTrue(result.pointers().isEmpty());
        assertFalse(ImpactSnapshots.json(java.util.Map.of("contracts/api.json", result)).contains("PRIVATE"));
    }

    @ParameterizedTest
    @EnumSource(SelectionImpact.Format.class)
    void arrayObjectOrderAndEquivalentDecimalsDoNotInventImpact(SelectionImpact.Format format) {
        var stable = compare(format, "{\"items\":[{\"a\":1e-2,\"b\":true},null]}", "{\"items\":[{\"b\":true,\"a\":0.0100},null]}");
        assertEquals(SelectionImpact.Failure.NONE, stable.failure());
        assertTrue(stable.pointers().isEmpty());
        assertEquals(Set.of("/items"), compare(format, "{\"items\":[1,null]}", "{\"items\":[1,null,2]}").pointers());
    }

    @Test
    void invalidPointerSyntaxAndAbsentBothSidesAreCallerErrors() {
        for (String pointer : Set.of("relative", "/bad~", "/bad~3", "/line\nbreak", "/" + "x".repeat(4096))) {
            assertThrows(IllegalArgumentException.class, () -> StructuredContracts.pointer(pointer));
        }
        assertThrows(IllegalArgumentException.class, () -> StructuredContracts.compare(SelectionImpact.Format.JSON, null, null));
        assertFalse(StructuredContracts.intersects("/api/*", "/api/known"), "Pointer '*' is a literal key, not a glob");
        assertTrue(StructuredContracts.intersects("/api/~1payments", "/api/~1payments/summary"));
        assertFalse(StructuredContracts.intersects("/api/~1payments", "/api/payments"));
    }

    private static SelectionImpact.Change compare(SelectionImpact.Format format, String before, String after) { return StructuredContracts.compare(format, bytes(before), bytes(after)); }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
