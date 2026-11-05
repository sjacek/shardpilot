package example;

class AlphaIT {
    @org.junit.jupiter.api.Test
    void alpha() { }

    @org.junit.jupiter.api.Nested
    class Nested {
        @org.junit.jupiter.api.Test
        void nested() { }
    }
}
