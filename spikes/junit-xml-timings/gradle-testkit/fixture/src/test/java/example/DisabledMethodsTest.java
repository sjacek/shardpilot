package example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class DisabledMethodsTest {
    @Disabled("F0 disabled-method measurement")
    @Test
    void first() { throw new AssertionError("must not run"); }

    @Disabled("F0 disabled-method measurement")
    @Test
    void second() { throw new AssertionError("must not run"); }
}
