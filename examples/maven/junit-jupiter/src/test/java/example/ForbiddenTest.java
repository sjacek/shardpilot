package example;

/** Deliberately fails if the adapter loses the consumer's existing file exclusion. */
class ForbiddenTest {
    @org.junit.jupiter.api.Test void excluded() { throw new AssertionError("Excluded test was executed"); }
}
