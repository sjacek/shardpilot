package example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Demonstrates keeping a Jupiter outer/nested family together while refreshing suite timings. */
class AlphaTest {
    @Test
    void outer() { }
    @Nested
    class Detail {
        @Test
        void nested() { }
    }
}
