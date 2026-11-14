package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real Gradle consumers export settings, supervise an external JVM and recheck retained evidence offline. */
class DiagnosticsFunctionalTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothDslsPrepareWithoutProducersAndRecheckAcrossConfigurationCacheReuse(boolean kotlin) throws Exception {
        fixture(kotlin, true);
        noProducers(runner("prepareTestDiagnostics").build());
        Path config = directory.resolve("build/shardpilot/test/diagnostics-config.json");
        var settings = Diagnostics.readSettings(config); assertFalse(Files.exists(settings.directory()));
        assertEquals(0, external(config, "run"));
        noProducers(runner("verifyTestDiagnostics").build());
        var repeated = runner("verifyTestDiagnostics").build(); noProducers(repeated);
        assertTrue(repeated.getOutput().contains("Reusing configuration cache."));
        var verification = repeated.task(":verifyTestDiagnostics");
        assertNotNull(verification, repeated.getOutput());
        assertEquals(TaskOutcome.SUCCESS, verification.getOutcome());
        Files.delete(settings.directory().resolve("process.json"));
        var missing = runner("verifyTestDiagnostics").buildAndFail(); noProducers(missing);
        assertTrue(missing.getOutput().contains("C4 DIAGNOSTICS_FAILED"));
        String report = Files.readString(settings.directory().resolve("report.json"));
        assertTrue(report.contains("PROCESS_RESULT_MISSING_OR_INVALID")); assertTrue(report.contains("\"status\":\"failed\""));
    }

    @Test void verifierNeverCreatesMissingSessionsOrSchedulesPreparation() throws Exception {
        fixture(false, true);
        var failed = runner("verifyTestDiagnostics").buildAndFail(); noProducers(failed);
        assertTrue(failed.getOutput().contains("C4 SESSION_MISSING"));
        assertFalse(Files.exists(directory.resolve("build/shardpilot/test/diagnostics-config.json")));
    }

    @Test void aDifferentCiAttemptCannotReuseACompletedProcess() throws Exception {
        fixture(false, true); runner("prepareTestDiagnostics").build();
        Path config = directory.resolve("build/shardpilot/test/diagnostics-config.json"); assertEquals(0, external(config, "run"));
        var failed = runner("verifyTestDiagnostics", "-Pshardpilot.diagnosticsAttempt=2").buildAndFail();
        noProducers(failed); assertTrue(failed.getOutput().contains("C4 DIAGNOSTICS_FAILED"));
        assertTrue(Files.readString(Diagnostics.readSettings(config).directory().resolve("report.json")).contains("INITIAL_EVIDENCE_INVALID"));
    }

    @Test void duplicateConfigurationFailsInsteadOfReplacingTheExpectedIdentity() throws Exception {
        fixture(false, true);
        Files.writeString(directory.resolve("build.gradle"), "\ntasks.named('test', Test).get().shardPilot.diagnostics { }\n", java.nio.file.StandardOpenOption.APPEND);
        assertTrue(runner("tasks", "--all").buildAndFail().getOutput().contains("diagnostics are already configured"));
    }

    @Test void applyingThePluginAloneAddsNoDiagnosticsTasks() throws Exception {
        fixture(false, false);
        String output = runner("tasks", "--all").build().getOutput();
        assertFalse(output.contains("prepareTestDiagnostics")); assertFalse(output.contains("verifyTestDiagnostics"));
    }

    @Test void anExternalNativeTimeoutRemainsBlockingThroughTheGradleVerifier() throws Exception {
        fixture(false, true);
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("sampleIntervalMillis = 50L", "sampleIntervalMillis = 50L; timeoutMillis = 150L"));
        Path sleeper = directory.resolve("Sleeper.java");
        Files.writeString(sleeper, "class Sleeper { public static void main(String[] args) throws InterruptedException { Thread.sleep(30000); } }\n");
        noProducers(runner("prepareTestDiagnostics").build());
        Path config = directory.resolve("build/shardpilot/test/diagnostics-config.json");
        // Source-file launch is a real JDK command with a deliberately long lifetime, not a mock timeout.
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        assertEquals(124, external(config, "run", executable, sleeper.toString()));
        var failed = runner("verifyTestDiagnostics").buildAndFail(); noProducers(failed);
        assertTrue(failed.getOutput().contains("C4 DIAGNOSTICS_FAILED"));
        assertTrue(Files.readString(Diagnostics.readSettings(config).directory().resolve("report.json")).contains("PROCESS_TIMEOUT"));
    }

    private void fixture(boolean kotlin, boolean enabled) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='diagnostics-testdata'\n");
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx256m\n");
        String groovy = "plugins { id 'java'; id 'io.github.sjacek.shardpilot' }\n";
        String kts = "import io.github.sjacek.shardpilot.ShardPilotExtension\nplugins { java; id(\"io.github.sjacek.shardpilot\") }\n";
        if (enabled) {
            groovy += "tasks.named('test', Test).get().shardPilot.diagnostics { sampleIntervalMillis = 50L }\n";
            kts += "tasks.named<Test>(\"test\").get().extensions.getByType<ShardPilotExtension>().diagnostics { sampleIntervalMillis.set(50L) }\n";
        }
        Files.writeString(directory.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? kts : groovy);
    }

    private org.gradle.testkit.runner.GradleRunner runner(String... tasks) {
        var args = new ArrayList<>(List.of("-Pshardpilot.diagnosticsRevision=" + "a".repeat(40), "-Pshardpilot.diagnosticsRunId=run-testdata", "-Pshardpilot.diagnosticsAttempt=1", "-Pshardpilot.diagnosticsNode=1"));
        args.addAll(List.of(tasks)); args.addAll(List.of("--offline", "--stacktrace", "--console=plain"));
        return VerifiedTestKit.create(directory).withPluginClasspath().withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion())).withArguments(args);
    }
    private static int external(Path config, String operation, String... nativeArguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        // A consumer's external runner uses the shipped JAR, without Gradle or dependency classpaths.
        String core = System.getProperty("shardpilot.test.pluginJar"); assertNotNull(core); assertTrue(Files.isRegularFile(Path.of(core)));
        var args = new ArrayList<>(List.of(java, "-cp", core, DiagnosticsCli.class.getName(), operation, config.toString()));
        if (operation.equals("run")) {
            args.add("--"); args.addAll(nativeArguments.length == 0 ? List.of(java, "-version") : List.of(nativeArguments));
        }
        Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS)); return process.exitValue();
    }
    private static void noProducers(BuildResult build) {
        assertTrue(build.getTasks().stream().noneMatch(task -> task.getPath().matches(".*:(test|compile.*|process.*Resources|testClasses|prepare.*Shard.*)")), build.getOutput());
    }
}
