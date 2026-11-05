package example;

class GammaTest {
    @org.junit.jupiter.api.Test
    void mustNeverRun() {
        throw new AssertionError("unselected class executed");
    }
}
