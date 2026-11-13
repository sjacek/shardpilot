package example.equivalence;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class GammaTest {
    @Disabled("equivalence fixture: expected discovery-time skip")
    @Test
    void disabled() { fail("a disabled test must never execute"); }

    @Test
    void ordinary() { }

    @Test
    void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
