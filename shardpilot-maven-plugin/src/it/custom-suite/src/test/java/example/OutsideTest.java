package example;

/** A native default include must not widen the file-based Spec-only eligibility. */
class OutsideTest {
    @org.junit.jupiter.api.Test
    void mustRemainOutsideTheSuite() {
        throw new AssertionError("native naming defaults widened the custom suite");
    }
}
