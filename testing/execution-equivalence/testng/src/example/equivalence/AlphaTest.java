package example.equivalence;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AlphaTest {
    private boolean initialized;

    @BeforeClass
    public void initialize() { initialized = true; }

    @DataProvider(name = "rows", propagateFailureAsTestFailure = true)
    public Object[][] rows() { return new Object[][] {{"same"}, {"same"}, {"other"}}; }

    @Test(dataProvider = "rows")
    public void row(String value) { assertTrue(initialized); assertFalse(value.isBlank()); }

    @Test
    public void ordinary() { assertTrue(initialized); }

    @Test
    public void intentionalFailure() { fail("equivalence fixture: expected assertion failure"); }
}
