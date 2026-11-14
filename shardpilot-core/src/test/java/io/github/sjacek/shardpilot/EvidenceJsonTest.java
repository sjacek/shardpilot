package io.github.sjacek.shardpilot;

import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JSON is a security boundary: accepting YAML or permissive duplicate/number syntax changes identity. */
class EvidenceJsonTest {
    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "false", "0", "-1", "1.25", "1e2", "[]", "{}", "[null,true,3]",
            "{\"a\":null,\"b\":[1,2]}", "\"escaped\\nline\\t\\u0041\\\\\\\"\"", "\"\\ud83d\\ude80\""})
    void validJsonRoundTripsWithoutChangingItsMeaning(String input) {
        Object value = EvidenceJson.parse(input);
        assertEquals(value, EvidenceJson.parse(EvidenceJson.write(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "01", "+1", "-", "1.", "1e", "NaN", "Infinity", "true false", "{}x", "[1,]",
            "{\"a\":1,}", "{a:1}", "{\"a\":null,\"a\":1}", "'string'", "\"\\x20\"", "\"\\ud800\"", "\"\\udc00\"",
            "\"\\u０００１\"", "\"unterminated", "\"literal\nnewline\"", "/* comment */{}", "[1,,2]"})
    void rejectsNonJsonSyntaxWithoutEchoingRejectedPayload(String input) {
        var failure = assertThrows(IllegalArgumentException.class, () -> EvidenceJson.parse(input));
        assertEquals("Invalid C2 JSON document", failure.getMessage()); assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 8})
    void theValueBudgetAcceptsItsBoundaryAndRejectsOneFewerValue(int maxValues) {
        // The array itself consumes one value, in addition to its scalar elements.
        String document = "[" + "0,".repeat(maxValues - 2) + "0]";
        assertEquals(Collections.nCopies(maxValues - 1, BigDecimal.ZERO), EvidenceJson.parse(document, maxValues));
        var failure = assertThrows(IllegalArgumentException.class, () -> EvidenceJson.parse(document, maxValues - 1));
        assertEquals("Invalid C2 JSON document", failure.getMessage()); assertNull(failure.getCause());
    }

    @Test
    void excessiveNestingIsRejectedBeforeRecursiveParsingCanExhaustTheStack() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> EvidenceJson.parse("[".repeat(1000) + "0" + "]".repeat(1000)));
        assertEquals("Invalid C2 JSON document", failure.getMessage()); assertNull(failure.getCause());
    }
}
