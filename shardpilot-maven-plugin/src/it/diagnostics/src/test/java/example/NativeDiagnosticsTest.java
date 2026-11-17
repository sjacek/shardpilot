package example;

import io.github.sjacek.shardpilot.Diagnostics;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A separate JVM supplies actual native process status; the following Maven goal verifies its evidence. */
class NativeDiagnosticsTest {
    @Test void observesAnIndependentNativeProcess() throws Exception {
        var settings = Diagnostics.readSettings(Path.of("target/shardpilot/diagnostics-config.json"));
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(NativeWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        // The fixture test asserts the requested native result. A later, independent Maven goal
        // must still reject exit 7; a green JUnit assertion is not evidence of a successful command.
        int expected = settings.directory().getFileName().toString().equals("failed") ? 7 : 0;
        assertEquals(expected, Diagnostics.run(settings, List.of(java, "-cp", classes, NativeWorker.class.getName(), Integer.toString(expected))));
    }
}
