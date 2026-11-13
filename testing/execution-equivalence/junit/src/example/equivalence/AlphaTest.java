package example.equivalence;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AlphaTest {
    @Test
    void ordinary() { assertTrue(true); }

    @ParameterizedTest(name = "row {index}: {0}")
    @ValueSource(strings = {"same", "same", "other"})
    void rows(String value) { assertFalse(value.isBlank()); }

    @Test
    void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }

    @Nested
    class NestedTests {
        @Test
        void nested() { assertTrue(true); }
    }
}
