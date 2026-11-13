package example.equivalence;

import org.testng.SkipException;
import org.testng.annotations.Test;

import static org.testng.Assert.fail;

public class GammaTest {
    @Test
    public void ordinary() { }

    @Test
    public void anotherOrdinary() { }

    @Test
    public void skipped() { throw new SkipException("equivalence fixture: expected runtime skip"); }

    @Test
    public void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
