package example.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A native test whose class identity is visible in the selection example's XML reports. */
class AlphaTest {

    @Test
    void retainsItsNativeOutcome() {
        assertEquals(1, Integer.parseInt("1"));
    }
}
