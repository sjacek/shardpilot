package example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AlphaTest {
    @Test void alpha() { }

    @Nested
    class NestedTests {
        @Test void nested() { }
    }
}
