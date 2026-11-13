package example.equivalence;

import org.testng.SkipException;
import org.testng.annotations.Test;

import static org.testng.Assert.fail;

public class BetaTest {
    @Test(invocationCount = 2)
    public void repeated() { }

    @Test
    public void skipped() { throw new SkipException("equivalence fixture: expected runtime skip"); }

    @Test
    public void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
