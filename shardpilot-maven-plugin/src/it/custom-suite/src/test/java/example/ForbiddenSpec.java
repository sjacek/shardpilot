package example;

/** A failed assertion makes loss of the maintained exclusion visible in the native job outcome. */
class ForbiddenSpec {
    @org.junit.jupiter.api.Test
    void mustRemainExcluded() {
        throw new AssertionError("maintained exclusions were bypassed");
    }
}
