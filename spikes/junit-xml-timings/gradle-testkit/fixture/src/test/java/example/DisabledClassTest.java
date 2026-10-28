package example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** A disabled container can have different report counters from individually disabled methods. */
@Disabled("F0 disabled-class measurement")
class DisabledClassTest {
    @Test void first() { throw new AssertionError("must not run"); }
    @Test void second() { throw new AssertionError("must not run"); }
}
