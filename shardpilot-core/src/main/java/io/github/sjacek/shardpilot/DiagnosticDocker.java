package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.sjacek.shardpilot.DiagnosticsFiles.object;

/** Only info/ps are issued. No create, pull, exec, stop, rm, prune or access to container payloads. */
final class DiagnosticDocker {
    private DiagnosticDocker() { }

    /** No delta-based exemption: a dedicated daemon must be empty, including created/stopped containers. */
    static Map<String, Object> snapshot(Diagnostics.Settings settings) {
        if (settings.dockerCommand().isEmpty()) return object("state", "disabled");
        try {
            String daemon = query(settings, "info", "--format", "{{.ID}}").strip();
            if (!daemon.matches("[A-Za-z0-9:._-]{1,128}")) throw Diagnostics.problem("INVALID_DOCKER_RESPONSE");
            String output = query(settings, "ps", "--all", "--quiet", "--no-trunc");
            // Full IDs avoid collisions. A partial, duplicate or oversized inventory cannot prove cleanup.
            var ids = new TreeSet<String>();
            for (String id : output.split("\\R")) {
                if (id.isEmpty()) continue;
                if (!id.matches("[a-f0-9]{64}") || !ids.add(id) || ids.size() > 256) throw Diagnostics.problem("INVALID_DOCKER_RESPONSE");
            }
            return object("state", "available", "daemonSha256", EvidenceFiles.digest(daemon.getBytes(StandardCharsets.UTF_8)), "containers", List.copyOf(ids));
        } catch (IOException | IllegalArgumentException invalid) { return object("state", "unavailable", "reason", "DOCKER_QUERY_FAILED"); }
    }

    private static String query(Diagnostics.Settings settings, String... arguments) throws IOException {
        // The prefix is explicit trusted configuration. Appended arguments are never expanded by a shell.
        var command = new ArrayList<>(settings.dockerCommand());
        command.add("--host"); command.add(settings.dockerHost()); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        var output = new AtomicReference<byte[]>();
        // Drain concurrently so a full pipe cannot bypass the process deadline. Retain at most limit+1
        // bytes: the extra byte distinguishes an over-limit response from an exact-length response.
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream()) { output.set(input.readNBytes(65_537)); }
            catch (IOException unavailable) { /* No stderr or command is copied to diagnostics. */ }
        }, "shardpilot-docker-read");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw Diagnostics.problem("DOCKER_QUERY_TIMEOUT");
            reader.join(1000);
            byte[] bytes = output.get();
            if (reader.isAlive() || process.exitValue() != 0 || bytes == null || bytes.length > 65_536) throw Diagnostics.problem("DOCKER_QUERY_FAILED");
            return EvidenceFiles.text(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw Diagnostics.problem("DOCKER_QUERY_INTERRUPTED");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
