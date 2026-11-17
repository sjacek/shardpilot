package io.github.sjacek.shardpilot;

import io.github.sjacek.shardpilot.testing.ConsumerBaselineProbe;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs portable CI-cohort validation and its real-core bridge without proprietary consumer fixtures. */
@NullMarked
class ConsumerBaselineFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void pinnedCiCohortAndPoliciesUseActualCore() throws Exception {
        run("test_collect_ci_baseline.py");
    }

    @Test
    void policyAcceptanceRejectsUntrustedInputs() throws Exception {
        run("test_verify_consumer_policy.py");
    }

    private void run(String pattern) throws Exception {
        Path log = directory.resolve(pattern + ".log");
        var builder = new ProcessBuilder(System.getProperty("os.name").startsWith("Windows") ? "python" : "python3",
                "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", pattern, "-v");
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(ConsumerBaselineProbe.class, SelectionEngine.class, org.snakeyaml.engine.v2.api.Load.class)) {
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        builder.environment().put("SHARDPILOT_SELECTION_CLASSPATH", String.join(File.pathSeparator, classpath));
        builder.environment().put("SHARDPILOT_SELECTION_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "CI baseline verification timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
