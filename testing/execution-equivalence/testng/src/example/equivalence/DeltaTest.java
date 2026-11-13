package example.equivalence;

import org.testng.annotations.Test;

import static org.testng.Assert.fail;

public class DeltaTest {
    @Test
    public void error() { throw new IllegalStateException("equivalence fixture: expected test error"); }

    @Test
    public void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
