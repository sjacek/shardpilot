package example.fixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Initializes marked temporary Git objects after dependency verification; not part of the selected suite. */
class FixtureBootstrapTest {
    @Test void createSyntheticCommittedChanges() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        assertFalse(Files.exists(root.resolve(".git")));
        Path launcher = root.resolve("target/test-classes/git-fixture/GitFixture.java"); assertTrue(Files.isRegularFile(launcher));
        Files.writeString(root.resolve(".shardpilot-fixture-testdata"), "synthetic Git fixture\n");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), launcher.toString(), root.toString(), "impact")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.waitFor(), output);
    }
}
