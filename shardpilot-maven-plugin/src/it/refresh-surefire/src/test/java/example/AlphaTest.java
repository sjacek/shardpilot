package example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The marker distinguishes native test execution from artifact-only history refreshes. */
class AlphaTest {
    @Test void outer() throws Exception { record(); }
    @Nested class NestedTests {
        @Test void nested() throws Exception { record(); }
    }
    static void record() throws Exception {
        Files.writeString(Path.of("target/executions.txt"), "executed\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
