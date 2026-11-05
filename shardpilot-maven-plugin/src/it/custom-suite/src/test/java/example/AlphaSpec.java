package example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Nested discovery must remain with the enclosing candidate despite native inner-class exclusions. */
class AlphaSpec {
    @Test
    void alpha() { }

    @Nested
    class NestedTests {
        @Test
        void nested() { }
    }
}
