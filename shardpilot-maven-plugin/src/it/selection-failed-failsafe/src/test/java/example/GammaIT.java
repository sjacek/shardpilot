package example;

class GammaIT {
    @org.junit.jupiter.api.Test
    void mustNeverRun() {
        throw new AssertionError("unselected class executed");
    }
}
