package example.equivalence;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

class BetaTest {
    @RepeatedTest(2)
    void repeated() { }

    @Test
    void aborted() { abort("equivalence fixture: expected runtime abort"); }

    @Test
    void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
