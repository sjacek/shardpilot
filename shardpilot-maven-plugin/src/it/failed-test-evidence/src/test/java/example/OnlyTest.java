package example;
class OnlyTest {
    @org.junit.jupiter.api.Test
    void only() { throw new AssertionError("intentional worker failure"); } }
