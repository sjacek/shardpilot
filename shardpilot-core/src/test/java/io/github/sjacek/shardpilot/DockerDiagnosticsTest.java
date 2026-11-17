package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.DiagnosticsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only CLI protocol under bounded output, a hung process and interruption; no real daemon. */
class DockerDiagnosticsTest {
    @TempDir Path directory;

    @Test void disabledInspectionDoesNotLaunchAClientOrCreateASession() {
        var settings = settings(directory.resolve("session"));
        assertEquals("disabled", DiagnosticDocker.snapshot(settings).get("state"));
        assertFalse(Files.exists(settings.directory()));
        assertFalse(Files.exists(directory.resolve("queries.txt")));
    }

    @Test void aCompleteLargeInventoryIsSortedAndRetainsEveryFullId() throws Exception {
        var ids = IntStream.range(0, 256).mapToObj(value -> String.format(java.util.Locale.ROOT, "%064x", 255 - value)).toList();
        Files.write(directory.resolve("containers"), ids);
        var result = DiagnosticDocker.snapshot(docker());
        assertEquals("available", result.get("state"));
        assertEquals(ids.stream().sorted().toList(), result.get("containers"));
        assertEquals(List.of("--host tcp://docker-testdata:2375 info --format {{.ID}}", "--host tcp://docker-testdata:2375 ps --all --quiet --no-trunc"),
                Files.readAllLines(directory.resolve("queries.txt")));
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "short-id", "too-many", "oversized", "invalid-utf8", "invalid-daemon"})
    void incompleteOrAmbiguousInventoriesCannotEstablishCleanup(String scenario) throws Exception {
        switch (scenario) {
            case "duplicate" -> Files.writeString(directory.resolve("containers"), "a".repeat(64) + "\n" + "a".repeat(64));
            case "short-id" -> Files.writeString(directory.resolve("containers"), "a".repeat(12));
            case "too-many" -> Files.write(directory.resolve("containers"), IntStream.range(0, 257).mapToObj(value -> String.format(java.util.Locale.ROOT, "%064x", value)).toList());
            case "oversized" -> Files.writeString(directory.resolve("containers"), "PRIVATE_".repeat(10_000));
            case "invalid-utf8" -> Files.write(directory.resolve("containers"), new byte[] {(byte) 0xc3, (byte) 0x28});
            case "invalid-daemon" -> Files.writeString(directory.resolve("daemon"), "PRIVATE_DAEMON\nsecond-line");
            default -> fail("Unknown test scenario");
        }
        var result = DiagnosticDocker.snapshot(docker());
        assertEquals("unavailable", result.get("state"));
        assertFalse(result.containsKey("containers"));
        assertFalse(EvidenceJson.write(result).contains("PRIVATE"));
        assertFalse(Files.exists(directory.resolve("session")));
    }

    @Test void aHungClientHasABoundedDeadlineAndIsTerminated() throws Exception {
        Files.writeString(directory.resolve("delay"), "controlled-delay");
        var settings = docker();
        var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> DiagnosticDocker.snapshot(settings));
        assertEquals("unavailable", result.get("state"));
        assertOwnedProcessStopped(directory.resolve("docker-pid"));
    }

    @Test void queryInterruptionRestoresTheFlagAndStopsOnlyTheOwnedClient() throws Exception {
        Files.writeString(directory.resolve("delay"), "controlled-delay");
        var settings = docker();
        var result = new AtomicReference<Map<String, Object>>();
        var error = new AtomicReference<Throwable>();
        var restored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { result.set(DiagnosticDocker.snapshot(settings)); restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.setDaemon(true); worker.start();
        try { awaitFile(directory.resolve("docker-pid")); worker.interrupt(); worker.join(6000); }
        finally { if (worker.isAlive()) worker.interrupt(); }
        assertFalse(worker.isAlive()); assertNull(error.get()); assertTrue(restored.get());
        assertNotNull(result.get()); assertEquals("unavailable", result.get().get("state"));
        assertOwnedProcessStopped(directory.resolve("docker-pid"));
    }

    private Diagnostics.Settings docker() throws Exception {
        var base = settings(directory.resolve("session"));
        return new Diagnostics.Settings(base.identity(), base.directory(), 0, 50, 100, null, null, false,
                nativeCommand("docker", directory.toString()), "tcp://docker-testdata:2375", true);
    }
}
