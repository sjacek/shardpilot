package example;

/** Independent measured family; shares the execution marker with the nested-family fixture. */
class BetaIT {
    @org.junit.jupiter.api.Test void beta() throws Exception { AlphaIT.record(); }
}
