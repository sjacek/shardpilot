package example.workflow;

/** Remains in full eligibility, but is absent from the smoke selection and both native outputs. */
class UnrelatedTest {
    @org.junit.jupiter.api.Test
    void unrelated() { org.junit.jupiter.api.Assertions.assertEquals(3, 1 + 2); }
}
