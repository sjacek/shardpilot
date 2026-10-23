package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DurationHistoryTest {
    @Test
    void readsCommentsWhitespaceCrLfAndNestedClassNames() {
        assertEquals(Map.of("example.AlphaIT", 12.5, "example.AlphaIT$Nested", 0.0),
                DurationHistory.parse("# seconds\r\n\r\n example.AlphaIT\t12.5 \r\nexample.AlphaIT$Nested\t0\r\n"));
    }

    @Test
    void emptyHistoryIsAValidColdStart() {
        assertTrue(DurationHistory.parse("\n# nothing yet\n").isEmpty());
    }

    @Test
    @SuppressWarnings("DataFlowIssue") // Mutation is deliberate: verify the immutable history contract.
    void parsedHistoryCannotBeMutated() {
        var history = DurationHistory.parse("ExampleTest\t1");
        assertThrows(UnsupportedOperationException.class, () -> history.put("AnotherTest", 2.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.BadIT 1", "example.BadIT\t", "example.BadIT\t1\textra",
            "example.BadIT\tNaN", "example.BadIT\tInfinity", "example.BadIT\t-1",
            "example.BadIT\t1e999", "../BadIT\t1", "example..BadIT\t1", "example.*\t1",
            "example.BadIT\t1\nexample.BadIT\t2"
    })
    void rejectsMalformedHistory(String text) {
        assertThrows(IllegalArgumentException.class, () -> DurationHistory.parse(text));
    }

    @Test
    void errorsIdentifyTheLineButDoNotEchoArbitraryContents() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> DurationHistory.parse("# metadata\nsecret-not-a-class\t1"));
        assertTrue(failure.getMessage().contains("line 2"));
        assertFalse(failure.getMessage().contains("secret-not-a-class"));
    }
}
