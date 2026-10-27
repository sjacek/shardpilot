package example;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Controlled failure: distinguish the failed attempt's cost from the eventual successful attempt. */
class FlakyTest {
    @Test
    void passesOnSecondAttempt() throws Exception {
        Path attempts = Path.of(System.getProperty("spike.attempts"));
        int attempt = Files.exists(attempts) ? Files.readAllLines(attempts).size() + 1 : 1;
        long start = System.nanoTime();
        Thread.sleep(attempt == 1 ? 700 : 200);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        Files.writeString(attempts, attempt + "\t" + seconds + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        assertTrue(attempt == 2, "intentional first-attempt failure for F0");
    }
}
