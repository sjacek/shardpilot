package spike;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static org.junit.jupiter.api.Assertions.*;

/** Standalone TestKit probe: retain reports in this spike's ignored build directory for inspection. */
class RetryXmlSpikeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void measuresRetryXml(boolean mergeReruns) throws IOException {
        Path source = Path.of(System.getProperty("spike.fixtureRoot"));
        Path project = Path.of(System.getProperty("spike.outputRoot"), "merge-" + mergeReruns);
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                // Fixture templates are data, not sources of this standalone TestKit project.
                String relative = source.relativize(file).toString();
                if (relative.endsWith(".template")) relative = relative.substring(0, relative.length() - ".template".length());
                Path destination = project.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var result = GradleRunner.create().withProjectDir(project.toFile()).withArguments(
                "clean", "test", "-PmergeReruns=" + mergeReruns, "--stacktrace", "--console=plain",
                "-Dorg.gradle.java.home=" + System.getProperty("java.home")).build();
        Files.writeString(project.resolve("build-output.txt"), result.getOutput());
        var attempts = Files.readAllLines(project.resolve("build/attempts.txt"));
        assertEquals(2, attempts.size(), "must execute a failed attempt and a successful retry");
        assertTrue(attempts.get(0).startsWith("1\t"));
        assertTrue(attempts.get(1).startsWith("2\t"));
        for (String type : new String[]{"FlakyTest", "DisabledClassTest", "DisabledMethodsTest", "IgnoredSpec"}) {
            assertTrue(Files.isRegularFile(project.resolve("build/test-results/test/TEST-example." + type + ".xml")));
        }
    }
}
