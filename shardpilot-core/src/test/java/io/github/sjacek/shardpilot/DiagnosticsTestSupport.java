package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Shared temporary-only fixtures; child JVMs need no framework, Gradle or Docker daemon. */
final class DiagnosticsTestSupport {
    private DiagnosticsTestSupport() { }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    static List<String> nativeCommand(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(javaExecutable(), "-cp",
                Path.of(DiagnosticFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), DiagnosticFixture.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    static Diagnostics.Settings settings(Path directory) {
        return new Diagnostics.Settings(new Diagnostics.Identity("a".repeat(40), "run-testdata", 1, "unit", "1"),
                directory, 0, 50, 100, null, null, false, List.of(), "", false);
    }

    static Map<String, Object> object(String text) {
        Map<?, ?> source = assertInstanceOf(Map.class, EvidenceJson.parse(text));
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(assertInstanceOf(String.class, key), value));
        return result;
    }

    static Map<String, Object> read(Path path) throws Exception { return object(Files.readString(path)); }

    static void awaitFile(Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        assertNotNull(parent, "Controlled fixture must have an existing parent directory");
        // Register before checking the file so creation between the check and a wait cannot be lost.
        // The fixture parent already exists; block on notifications rather than repeatedly sleeping.
        try (var events = parent.getFileSystem().newWatchService()) {
            parent.register(events, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(file) || Files.size(file) == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                var event = events.poll(remaining, TimeUnit.NANOSECONDS);
                if (event == null) break;
                event.pollEvents(); // Any event, including overflow, requires checking the actual file.
                if (!event.reset()) break;
            }
            assertTrue(Files.isRegularFile(file) && Files.size(file) > 0, "Controlled fixture did not start");
        }
    }

    static void assertOwnedProcessStopped(Path pidFile) throws Exception {
        awaitFile(pidFile);
        var process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
        try {
            if (process.isPresent()) process.get().onExit().get(3, TimeUnit.SECONDS);
            assertTrue(process.isEmpty() || !process.get().isAlive(), "Owned query process was left alive");
        }
        finally { process.filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly); }
    }
}
