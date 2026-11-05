package example;

class AlphaTest {
    @org.junit.jupiter.api.Test
    void alpha() { }

    @org.junit.jupiter.api.Nested
    class Nested {
        @org.junit.jupiter.api.Test
        void nested() { }
    }
}
