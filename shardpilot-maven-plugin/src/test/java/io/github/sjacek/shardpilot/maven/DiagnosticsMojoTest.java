package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.Diagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Native failures and missing evidence remain blocking through the Maven boundary. */
class DiagnosticsMojoTest {
    @TempDir Path directory;

    @Test void preparationExportsSettingsWithoutStartingTheNativeProcess() throws Exception {
        var mojo = configured(new PrepareDiagnosticsMojo()); mojo.execute();
        var settings = Diagnostics.readSettings(mojo.diagnosticsConfigurationFile.toPath());
        assertEquals("run-testdata", settings.identity().runId());
        assertFalse(Files.exists(settings.directory()));
    }

    @Test void verifiesNativeSuccessAndReplacesItWhenProcessEvidenceDisappears() throws Exception {
        var prepared = configured(new PrepareDiagnosticsMojo()); prepared.execute();
        var settings = Diagnostics.readSettings(prepared.diagnosticsConfigurationFile.toPath());
        assertEquals(0, Diagnostics.run(settings, List.of(java(), "-version")));
        var verifier = configured(new VerifyDiagnosticsMojo()); verifier.execute();
        Files.delete(settings.directory().resolve("process.json"));
        assertThrows(MojoFailureException.class, verifier::execute);
        assertTrue(Files.readString(settings.directory().resolve("report.json")).contains("PROCESS_RESULT_MISSING_OR_INVALID"));
    }

    @Test void failedNativeProcessCannotBecomeASuccessfulMavenVerification() throws Exception {
        var prepared = configured(new PrepareDiagnosticsMojo()); prepared.execute();
        var settings = Diagnostics.readSettings(prepared.diagnosticsConfigurationFile.toPath());
        assertNotEquals(0, Diagnostics.run(settings, List.of(java(), "-shardpilot-invalid-testdata")));
        assertThrows(MojoFailureException.class, configured(new VerifyDiagnosticsMojo())::execute);
        assertTrue(Files.readString(settings.directory().resolve("report.json")).contains("NATIVE_PROCESS_FAILED"));
    }

    @Test void anotherAttemptCannotAdoptAPreviousSuccessfulSession() throws Exception {
        var prepared = configured(new PrepareDiagnosticsMojo()); prepared.execute();
        var settings = Diagnostics.readSettings(prepared.diagnosticsConfigurationFile.toPath());
        assertEquals(0, Diagnostics.run(settings, List.of(java(), "-version")));
        var verifier = configured(new VerifyDiagnosticsMojo()); verifier.diagnosticsAttempt = 2;
        assertThrows(MojoFailureException.class, verifier::execute);
        assertTrue(Files.readString(settings.directory().resolve("report.json")).contains("INITIAL_EVIDENCE_INVALID"));
    }

    @Test void missingSessionsAndSharedDockerConfigurationFailWithoutEchoingPrivateArguments() {
        var missing = configured(new VerifyDiagnosticsMojo());
        assertEquals("C4 SESSION_MISSING", assertThrows(MojoFailureException.class, missing::execute).getMessage());
        var shared = configured(new PrepareDiagnosticsMojo()); shared.diagnosticsDockerCommand = List.of("PRIVATE_DOCKER"); shared.diagnosticsDockerHost = "tcp://docker-testdata:2375";
        var failure = assertThrows(MojoFailureException.class, shared::execute);
        assertEquals("C4 DEDICATED_DOCKER_REQUIRED", failure.getMessage()); assertNull(failure.getCause());
        assertFalse(Files.exists(shared.diagnosticsConfigurationFile.toPath()));
    }

    @Test void preparationKeepsExplicitMemoryAndDockerSettingsOffline() throws Exception {
        var mojo = configured(new PrepareDiagnosticsMojo());
        mojo.diagnosticsJobCgroup = directory.resolve("job-testdata").toFile();
        mojo.diagnosticsDindCgroup = directory.resolve("dind-testdata").toFile();
        mojo.diagnosticsHostMemory = true; mojo.diagnosticsTimeoutMillis = 1234;
        mojo.diagnosticsSampleIntervalMillis = 200; mojo.diagnosticsMaxSamples = 7;
        // A missing executable proves that preparation does not contact the declared daemon.
        mojo.diagnosticsDockerCommand = List.of("PRIVATE_MISSING_DOCKER");
        mojo.diagnosticsDockerHost = "tcp://docker-testdata:2375"; mojo.diagnosticsDedicatedDocker = true;
        mojo.execute();
        var settings = Diagnostics.readSettings(mojo.diagnosticsConfigurationFile.toPath());
        assertEquals(mojo.diagnosticsJobCgroup.toPath(), settings.jobCgroup()); assertEquals(mojo.diagnosticsDindCgroup.toPath(), settings.dindCgroup());
        assertTrue(settings.hostMemory()); assertEquals(1234, settings.timeoutMillis()); assertEquals(200, settings.sampleIntervalMillis()); assertEquals(7, settings.maxSamples());
        assertEquals(mojo.diagnosticsDockerCommand, settings.dockerCommand()); assertEquals(mojo.diagnosticsDockerHost, settings.dockerHost()); assertTrue(settings.dedicatedDocker());
        assertFalse(Files.exists(settings.directory()));
    }

    @Test void aConfigurationWriteFailureExposesNeitherPrivatePathNorCause() throws Exception {
        Path blocked = directory.resolve("PRIVATE_BLOCKED_PARENT"); Files.writeString(blocked, "unchanged");
        var mojo = configured(new PrepareDiagnosticsMojo()); mojo.diagnosticsConfigurationFile = blocked.resolve("config.json").toFile();
        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("C4 DIAGNOSTICS_OUTPUT_UNAVAILABLE", failure.getMessage()); assertNull(failure.getCause());
        assertEquals("unchanged", Files.readString(blocked)); assertFalse(Files.exists(mojo.diagnosticsDirectory.toPath()));
    }

    @Test void anUnsafeReportDestinationFailsAndRevokesTheEarlierJsonVerdict() throws Exception {
        var prepared = configured(new PrepareDiagnosticsMojo()); prepared.execute();
        var settings = Diagnostics.readSettings(prepared.diagnosticsConfigurationFile.toPath());
        assertEquals(0, Diagnostics.run(settings, List.of(java(), "-version")));
        Path html = settings.directory().resolve("report.html"); Files.delete(html); Files.createDirectory(html);
        var failure = assertThrows(MojoFailureException.class, configured(new VerifyDiagnosticsMojo())::execute);
        assertEquals("C4 UNSAFE_PATH", failure.getMessage()); assertNull(failure.getCause());
        assertFalse(Files.exists(settings.directory().resolve("report.json"))); assertTrue(Files.isDirectory(html));
    }

    private <T extends AbstractDiagnosticsMojo> T configured(T mojo) {
        mojo.diagnosticsConfigurationFile = directory.resolve("config.json").toFile(); mojo.diagnosticsDirectory = directory.resolve("session").toFile();
        mojo.diagnosticsRevision = "a".repeat(40); mojo.diagnosticsRunId = "run-testdata"; mojo.diagnosticsAttempt = 1; mojo.diagnosticsSuite = "unit"; mojo.diagnosticsNode = "1";
        return mojo;
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }
}
