package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Native exit/timeout/kill evidence is independent from XML, with explicit unknown metrics and read-only Docker. */
class DiagnosticsTest {
    @TempDir Path directory;
    private int sessions;

    @Test void preparationIsOfflineAndRoundTripsWithoutStartingASession() throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        Path config = directory.resolve("settings.json");
        Diagnostics.prepare(settings, config);
        assertEquals(settings, Diagnostics.readSettings(config));
        assertFalse(Files.exists(settings.directory()));
        assertThrows(Diagnostics.Problem.class, () -> Diagnostics.prepare(settings, settings.directory().resolve("config.json")));
        Files.writeString(config, settings.json().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"PRIVATE_EXTRA\":true"));
        var failure = assertThrows(Diagnostics.Problem.class, () -> Diagnostics.readSettings(config));
        assertFalse(failure.getMessage().contains("PRIVATE")); assertNull(failure.getCause());
    }

    @ParameterizedTest @ValueSource(ints = {0, 7, 137})
    void preservesNativeFailuresAndDoesNotInferOomFromExit137(int exit) throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        var command = new ArrayList<>(command("exit", Integer.toString(exit))); command.add("PRIVATE_ARGUMENT");
        int observedExit = Diagnostics.run(settings, command);
        assertEquals(exit, observedExit, () -> {
            try { return Files.readString(settings.directory().resolve("report.json")); }
            catch (java.io.IOException missing) { return "C4 report missing"; }
        });
        var result = Diagnostics.finish(settings); assertEquals(exit == 0, result.passed());
        var report = json(result.json());
        assertEquals(Integer.toString(exit), section(report, "process").get("exitCode").toString());
        assertNull(section(report, "oomKillsDelta").get("job"));
        for (String text : List.of(result.json(), result.html())) {
            assertFalse(text.contains("PRIVATE_ARGUMENT")); assertFalse(text.contains(directory.toString()));
        }
        assertEquals("disabled", section(section(report, "containers"), "final").get("state"));
    }

    @Test void timeoutStopsTheOwnedProcessAndFailsTheIndependentFinalCheck() throws Exception {
        var settings = settings(200, List.of(), "", false, null);
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertEquals(124, Diagnostics.run(settings, command("wait", directory.toString(), "30000"))));
        var result = Diagnostics.finish(settings); assertFalse(result.passed());
        assertEquals("timeout", section(json(result.json()), "process").get("state"));
        assertTrue(result.json().contains("PROCESS_TIMEOUT"));
    }

    @RepeatedTest(5) void aQuickSuccessfulProcessDoesNotProduceAnOrphanWarning() throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        assertTrue(Diagnostics.finish(settings).passed());
    }

    @Test void interruptionPreservesFailureAndRestoresTheCallingThreadsInterruptFlag() throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        var exit = new AtomicInteger(-1); var failure = new AtomicReference<Throwable>(); var restored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread observer = new Thread(() -> {
            try { exit.set(Diagnostics.run(settings, command("wait", directory.toString(), "30000"))); restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable error) { failure.set(error); }
        });
        observer.setDaemon(true); observer.start();
        try { await(directory.resolve("native-marker")); observer.interrupt(); observer.join(6000); }
        finally { if (observer.isAlive()) observer.interrupt(); }
        assertFalse(observer.isAlive()); assertNull(failure.get()); assertEquals(130, exit.get()); assertTrue(restored.get());
        assertEquals("interrupted", section(json(Diagnostics.finish(settings).json()), "process").get("state"));
    }

    @Test void aKilledSupervisorLeavesPartialEvidenceThatAfterScriptCannotTurnIntoSuccess() throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        Path config = directory.resolve("config.json"); Diagnostics.prepare(settings, config);
        String core = Path.of(Diagnostics.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String fixtures = Path.of(DiagnosticFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var arguments = new ArrayList<>(List.of(java(), "-cp", core + java.io.File.pathSeparator + fixtures, DiagnosticsCli.class.getName(), "run", config.toString(), "--"));
        arguments.addAll(command("wait", directory.toString(), "30000"));
        Process observer = new ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            await(directory.resolve("native-marker")); await(settings.directory().resolve("samples.jsonl"));
            observer.destroyForcibly(); assertTrue(observer.waitFor(5, TimeUnit.SECONDS));
            var result = Diagnostics.finish(settings);
            assertFalse(result.passed()); assertTrue(result.json().contains("PROCESS_RESULT_MISSING_OR_INVALID"));
            assertEquals("incomplete", section(json(result.json()), "process").get("state"));
            assertNull(section(json(result.json()), "process").get("exitCode"));
        } finally {
            observer.destroyForcibly();
            if (Files.exists(directory.resolve("native-marker"))) ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("native-marker")))).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test void anInitialContainerBlocksTestsAndOnlyReadOnlyDockerCommandsAreIssued() throws Exception {
        Files.writeString(directory.resolve("containers"), "a".repeat(64) + "\n");
        var settings = docker();
        assertEquals(1, Diagnostics.run(settings, command("wait", directory.toString(), "1")));
        assertFalse(Files.exists(directory.resolve("native-marker")));
        assertTrue(Diagnostics.finish(settings).json().contains("DOCKER_INITIAL_NOT_CLEAN"));
        readonlyDockerQueries(); assertTrue(Files.exists(directory.resolve("containers")));
    }

    @ParameterizedTest @ValueSource(strings = {"leak", "swap", "unavailable"})
    void aSuccessfulNativeProcessCannotHideLeftoversChangedDaemonOrUnavailableDocker(String mode) throws Exception {
        var settings = docker();
        assertEquals(1, Diagnostics.run(settings, command(mode, directory.toString())));
        var result = Diagnostics.finish(settings); assertFalse(result.passed());
        String reason = switch (mode) { case "leak" -> "CONTAINERS_REMAIN"; case "swap" -> "DOCKER_DAEMON_CHANGED"; default -> "DOCKER_FINAL_UNAVAILABLE"; };
        assertTrue(result.json().contains(reason));
        assertEquals("0", section(json(result.json()), "process").get("exitCode").toString());
        assertFalse(result.json().contains("PRIVATE_DOCKER_ERROR")); assertFalse(result.html().contains("PRIVATE_DOCKER_ERROR"));
        readonlyDockerQueries();
    }

    @Test void aCleanDaemonPassesAndARepeatedCheckDetectsLaterStoppedOrRunningContainers() throws Exception {
        var settings = docker();
        assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        assertTrue(Diagnostics.finish(settings).passed());
        Files.writeString(directory.resolve("containers"), "a".repeat(64) + "\n" + "b".repeat(64) + "\n");
        var changed = Diagnostics.finish(settings); assertFalse(changed.passed());
        assertTrue(changed.json().contains("CONTAINERS_REMAIN"));
        assertEquals(2, ((List<?>) section(section(json(changed.json()), "containers"), "final").get("containers")).size());
        readonlyDockerQueries(); assertEquals(2, Files.readAllLines(directory.resolve("containers")).size());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void readsCgroupVersionsWithoutMixingJobDindAndHostScopes(int version) throws Exception {
        Path group = directory.resolve("job-testdata"); Files.createDirectory(group);
        Files.writeString(group.resolve(version == 2 ? "memory.current" : "memory.usage_in_bytes"), "1024");
        Files.writeString(group.resolve(version == 2 ? "memory.max" : "memory.limit_in_bytes"), version == 2 ? "max" : "9223372036854771712");
        Files.writeString(group.resolve(version == 2 ? "memory.events" : "memory.oom_control"), "oom_kill 5\n");
        var counters = DiagnosticMemory.cgroup(group);
        assertEquals("available", counters.get("state")); assertEquals(1024L, counters.get("usedBytes")); assertNull(counters.get("limitBytes")); assertEquals(5L, counters.get("oomKills"));
        assertEquals("unavailable", DiagnosticMemory.cgroup(directory.resolve("missing")).get("state"));
        Files.writeString(group.resolve(version == 2 ? "memory.events" : "memory.oom_control"), "no_oom_counter 0\n");
        assertNull(DiagnosticMemory.cgroup(group).get("oomKills"));
    }

    @Test void oomRequiresAnObservedCounterIncreaseInTheDeclaredScope() throws Exception {
        Path group = directory.resolve("job-cgroup-testdata"); Files.createDirectory(group);
        Files.writeString(group.resolve("memory.current"), "100"); Files.writeString(group.resolve("memory.max"), "1000"); Files.writeString(group.resolve("memory.events"), "oom_kill 5\n");
        var settings = settings(0, List.of(), "", false, group);
        assertEquals(137, Diagnostics.run(settings, command("oom", group.toString())));
        var report = json(Diagnostics.finish(settings).json());
        assertEquals("1", section(report, "oomKillsDelta").get("job").toString()); assertNull(section(report, "oomKillsDelta").get("dind"));
    }

    @Test void counterResetOrCgroupVersionChangeDoesNotEstablishAnOomDelta() throws Exception {
        Path group = directory.resolve("job-cgroup-testdata"); Files.createDirectory(group);
        Files.writeString(group.resolve("memory.current"), "100"); Files.writeString(group.resolve("memory.max"), "1000");
        Files.writeString(group.resolve("memory.events"), "oom_kill 5\n");
        var settings = settings(0, List.of(), "", false, group);
        assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        Files.writeString(group.resolve("memory.events"), "oom_kill 1\n");
        assertNull(section(json(Diagnostics.finish(settings).json()), "oomKillsDelta").get("job"));
        Files.delete(group.resolve("memory.current"));
        Files.writeString(group.resolve("memory.usage_in_bytes"), "100"); Files.writeString(group.resolve("memory.limit_in_bytes"), "1000");
        Files.writeString(group.resolve("memory.oom_control"), "oom_kill 9\n");
        assertNull(section(json(Diagnostics.finish(settings).json()), "oomKillsDelta").get("job"));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void linuxCollectorReadsTheRealChildProcessTreeAndKeepsHostOptInSeparate() throws Exception {
        var base = settings(0, List.of(), "", false, null);
        var settings = new Diagnostics.Settings(base.identity(), base.directory(), 0, 50, 100, null, null, true, List.of(), "", false);
        assertEquals(0, Diagnostics.run(settings, command("wait", directory.toString(), "300")));
        var report = json(Diagnostics.finish(settings).json());
        var peaks = section(section(report, "samples"), "peakBytes");
        assertTrue(Long.parseLong(peaks.get("process").toString()) > 0);
        assertTrue(Long.parseLong(peaks.get("host").toString()) > 0);
        assertFalse(peaks.containsKey("job")); assertFalse(peaks.containsKey("dind"));
    }

    @ParameterizedTest @ValueSource(strings = {"localhost:2375", "localhost", "https://docker-testdata:2376", "tcp://PRIVATE:SECRET@localhost:2375", "tcp://localhost:2375?PRIVATE=SECRET"})
    void unsupportedOrCredentialBearingDockerEndpointsFailWithoutEchoingConfiguration(String endpoint) {
        var failure = assertThrows(Diagnostics.Problem.class, () -> settings(0, List.of("docker"), endpoint, true, null));
        assertEquals("C4 INVALID_DOCKER_CONFIGURATION", failure.getMessage()); assertNull(failure.getCause());
    }

    @Test void malformedDockerOutputFailsClosedAndRemainsPrivate() throws Exception {
        Files.writeString(directory.resolve("containers"), "PRIVATE_INVALID_CONTAINER_ID");
        var settings = docker();
        assertEquals(1, Diagnostics.run(settings, command("wait", directory.toString(), "1")));
        assertFalse(Files.exists(directory.resolve("native-marker")));
        var report = Diagnostics.finish(settings);
        assertFalse(report.passed()); assertTrue(report.json().contains("DOCKER_INITIAL_NOT_CLEAN"));
        assertFalse(report.json().contains("PRIVATE_INVALID_CONTAINER_ID")); assertFalse(report.html().contains("PRIVATE_INVALID_CONTAINER_ID"));
        readonlyDockerQueries();
    }

    @Test void partialSamplesAndDifferentAttemptsInvalidateEarlierSuccess() throws Exception {
        var settings = settings(0, List.of(), "", false, null);
        assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        Files.writeString(settings.directory().resolve("samples.jsonl"), "{\"PRIVATE_TRUNCATED", java.nio.file.StandardOpenOption.APPEND);
        var partial = Diagnostics.finish(settings); assertFalse(partial.passed()); assertTrue(partial.json().contains("SAMPLES_INCOMPLETE"));
        assertFalse(partial.json().contains("PRIVATE_TRUNCATED"));
        assertEquals(partial.json(), Files.readString(settings.directory().resolve("report.json")));
        var other = new Diagnostics.Settings(new Diagnostics.Identity("b".repeat(40), "run-testdata", 2, "unit", "1"), settings.directory(), 0, 50, 100, null, null, false, List.of(), "", false);
        assertTrue(Diagnostics.finish(other).json().contains("INITIAL_EVIDENCE_INVALID"));
    }

    @Test void boundedSamplingStopsGrowingWithoutDisablingTheNativeDeadline() throws Exception {
        var base = settings(350, List.of(), "", false, null);
        var settings = new Diagnostics.Settings(base.identity(), base.directory(), base.timeoutMillis(), 50, 2, null, null, false, List.of(), "", false);
        assertEquals(124, Diagnostics.run(settings, command("wait", directory.toString(), "30000")));
        assertEquals(2, Files.readAllLines(settings.directory().resolve("samples.jsonl")).size());
        var sample = section(json(Diagnostics.finish(settings).json()), "samples");
        assertEquals(Boolean.TRUE, sample.get("limitReached"));
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "9223372036854775808", "PRIVATE_COUNTER", "1.5"})
    void malformedOrOverflowingCountersRemainUnknown(String value) throws Exception {
        Files.writeString(directory.resolve("memory.current"), value); Files.writeString(directory.resolve("memory.max"), "max");
        var result = DiagnosticMemory.cgroup(directory);
        assertEquals("unavailable", result.get("state")); assertFalse(EvidenceJson.write(result).contains("PRIVATE"));
    }

    @Test void rejectsImplicitSharedDockerAndSingleUseSessionReuse() throws Exception {
        assertThrows(Diagnostics.Problem.class, () -> settings(0, List.of("docker"), "tcp://localhost:2375", false, null));
        assertThrows(Diagnostics.Problem.class, () -> settings(0, List.of("docker"), "tcp://PRIVATE:SECRET@localhost:2375", true, null));
        var settings = settings(0, List.of(), "", false, null);
        assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        String retained = Files.readString(settings.directory().resolve("process.json"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> Diagnostics.run(settings, command("exit", "0")));
        assertEquals(retained, Files.readString(settings.directory().resolve("process.json")));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportSymlinksCannotOverwriteUnrelatedBytes() throws Exception {
        var settings = settings(0, List.of(), "", false, null); assertEquals(0, Diagnostics.run(settings, command("exit", "0")));
        Path target = directory.resolve("private.txt"); Files.writeString(target, "PRIVATE_UNCHANGED");
        Files.delete(settings.directory().resolve("report.json")); Files.createSymbolicLink(settings.directory().resolve("report.json"), target);
        assertThrows(Diagnostics.Problem.class, () -> Diagnostics.finish(settings)); assertEquals("PRIVATE_UNCHANGED", Files.readString(target));
    }

    private Diagnostics.Settings docker() throws Exception { return settings(0, command("docker", directory.toString()), "tcp://docker-testdata:2375", true, null); }
    private Diagnostics.Settings settings(long timeout, List<String> docker, String host, boolean dedicated, Path job) {
        Path root = directory.resolve("session-" + ++sessions);
        return new Diagnostics.Settings(new Diagnostics.Identity("a".repeat(40), "run-testdata", 1, "unit", "1"), root, timeout, 50, 100, job, null, false, docker, host, dedicated);
    }
    private static List<String> command(String... args) throws Exception {
        var result = new ArrayList<>(List.of(java(), "-cp", Path.of(DiagnosticFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), DiagnosticFixture.class.getName()));
        result.addAll(List.of(args)); return result;
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(); }
    private static void await(Path file) throws Exception {
        DiagnosticsTestSupport.awaitFile(file);
    }
    private void readonlyDockerQueries() throws Exception {
        var queries = Files.readAllLines(directory.resolve("queries.txt")); assertFalse(queries.isEmpty());
        assertTrue(queries.stream().allMatch(line -> line.equals("--host tcp://docker-testdata:2375 info --format {{.ID}}") || line.equals("--host tcp://docker-testdata:2375 ps --all --quiet --no-trunc")), queries.toString());
    }
    private static Map<?, ?> json(String text) { return assertInstanceOf(Map.class, EvidenceJson.parse(text)); }
    private static Map<?, ?> section(Map<?, ?> report, String name) { return assertInstanceOf(Map.class, report.get(name)); }
}
