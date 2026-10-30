package example;

/** Remains eligible, but skipped execution must retain its previous timing without a new sample. */
@org.junit.jupiter.api.Disabled("No timing sample may replace the previous record")
class DisabledTest {
    @org.junit.jupiter.api.Test
    void skipped() { }
}
