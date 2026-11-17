package example.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unrelated eligible class; impact selection must exclude it. */
class UnusedTest {
    @Test
    void remainsAvailableForFullRuns() {
        assertEquals(4, Integer.parseInt("4"));
    }
}
