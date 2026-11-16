package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the public replay harness against actual core classes, not a mocked selection response. */
class SelectorComparisonFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void frozenPolicyReplayHasReviewedDifferencesAndStablePlans() throws Exception {
        Path log = directory.resolve("comparison.log");
        var builder = new ProcessBuilder(System.getProperty("os.name").startsWith("Windows") ? "python" : "python3",
                "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_compare_selectors.py", "-v");
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(io.github.sjacek.shardpilot.testing.SelectionComparisonProbe.class,
                SelectionEngine.class, org.snakeyaml.engine.v2.api.Load.class)) {
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        builder.environment().put("SHARDPILOT_SELECTION_CLASSPATH", String.join(File.pathSeparator, classpath));
        builder.environment().put("SHARDPILOT_SELECTION_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Comparison replay timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
