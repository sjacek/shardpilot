package example;

/** Independent measured family; shares the execution marker with the nested-family fixture. */
class BetaTest {
    @org.junit.jupiter.api.Test void beta() throws Exception { AlphaTest.record(); }
}
