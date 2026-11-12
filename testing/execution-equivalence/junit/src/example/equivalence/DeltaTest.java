package example.equivalence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class DeltaTest {
    @Test
    void error() { throw new IllegalStateException("equivalence fixture: expected test error"); }

    @Test
    void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
