package example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Distinct body times reveal whether the enclosing suite also charges for its nested suite. */
class OuterTest {
    @Test void outer() throws InterruptedException { Thread.sleep(200); }

    @Nested
    class Inner {
        @Test void nested() throws InterruptedException { Thread.sleep(700); }
    }
}
