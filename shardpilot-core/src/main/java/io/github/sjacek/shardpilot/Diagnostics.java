package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.DiagnosticsFiles.object;

/**
 * Optional C4 supervisor and repeatable after-script verification. No test discovery, compilation,
 * container creation/deletion or interpretation of successful XML as a successful native process.
 */
@NullMarked
public final class Diagnostics {
    private Diagnostics() { }

    /**
     * Independently configured identity of one attempt and one parallel job.
     * @param revision full lowercase Git SHA-1/SHA-256
     * @param runId CI run identity
     * @param attempt positive attempt number
     * @param suite test suite identity
     * @param node job/shard identity
     */
    public record Identity(String revision, String runId, int attempt, String suite, String node) {
        /** Validates identities without echoing rejected values. */
        public Identity {
            if (!revision.matches("[a-f0-9]{40}|[a-f0-9]{64}") || attempt < 1 || invalidIdentifier(runId) || invalidIdentifier(suite) || invalidIdentifier(node)) throw problem("INVALID_IDENTITY");
        }
        Map<String, Object> json() { return object("revision", revision, "runId", runId, "attempt", attempt, "suite", suite, "node", node); }
    }

    /**
     * Trusted local settings, never inferred from downloaded diagnostics. Memory sources are optional;
     * Docker checks require an explicit endpoint and the consumer's dedicated-daemon declaration.
     * @param identity expected attempt/job
     * @param directory fresh exclusively owned output directory
     * @param timeoutMillis zero disables the deadline; otherwise at most one day
     * @param sampleIntervalMillis sampling interval, 50 ms to one minute
     * @param maxSamples maximum retained samples, at most 10000
     * @param jobCgroup explicit job cgroup directory, or null
     * @param dindCgroup explicit DinD cgroup directory, or null
     * @param hostMemory whether to read allowlisted host memory counters
     * @param dockerCommand Docker executable/prefix, empty disables Docker checks
     * @param dockerHost explicit Docker endpoint, empty when disabled
     * @param dedicatedDocker whether the consumer guarantees exclusive daemon ownership
     */
    public record Settings(Identity identity, Path directory, long timeoutMillis, long sampleIntervalMillis, int maxSamples,
                           @Nullable Path jobCgroup, @Nullable Path dindCgroup, boolean hostMemory,
                           List<String> dockerCommand, String dockerHost, boolean dedicatedDocker) {
        /** Bounds collection and validates configuration before issuing any Docker command. */
        public Settings {
            directory = directory.toAbsolutePath().normalize(); dockerCommand = List.copyOf(dockerCommand);
            if (timeoutMillis < 0 || timeoutMillis > 86_400_000 || sampleIntervalMillis < 50 || sampleIntervalMillis > 60_000 || maxSamples < 1 || maxSamples > 10_000) throw problem("INVALID_LIMITS");
            if (jobCgroup != null) jobCgroup = jobCgroup.toAbsolutePath().normalize();
            if (dindCgroup != null) dindCgroup = dindCgroup.toAbsolutePath().normalize();
            rejectCounterOverlap(directory, jobCgroup);
            rejectCounterOverlap(directory, dindCgroup);
            if (dockerCommand.isEmpty()) {
                if (!dockerHost.isEmpty() || dedicatedDocker) throw problem("INVALID_DOCKER_CONFIGURATION");
            } else {
                if (!dedicatedDocker || dockerCommand.size() > 16 || dockerCommand.stream().anyMatch(value -> value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl))) throw problem("DEDICATED_DOCKER_REQUIRED");
                try {
                    var uri = java.net.URI.create(dockerHost);
                    if (uri.getScheme() == null || !Set.of("tcp", "unix", "npipe").contains(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                            || dockerHost.length() > 1024 || dockerHost.chars().anyMatch(Character::isISOControl)) throw problem("INVALID_DOCKER_CONFIGURATION");
                } catch (IllegalArgumentException invalid) { throw problem("INVALID_DOCKER_CONFIGURATION"); }
            }
        }

        /**
         * Deterministic private configuration; unlike reports this contains local paths and executable arguments.
         * @return UTF-8-compatible JSON
         */
        public String json() {
            return EvidenceJson.write(object("schemaVersion", 1, "identity", identity.json(), "directory", directory.toString(),
                    "timeoutMillis", timeoutMillis, "sampleIntervalMillis", sampleIntervalMillis, "maxSamples", maxSamples,
                    "jobCgroup", jobCgroup == null ? null : jobCgroup.toString(), "dindCgroup", dindCgroup == null ? null : dindCgroup.toString(),
                    "hostMemory", hostMemory, "dockerCommand", dockerCommand, "dockerHost", dockerHost, "dedicatedDocker", dedicatedDocker));
        }
    }

    /**
     * Immutable JSON/HTML pair from one evaluation. Native failures are never converted to success.
     * @param passed whether the observed process and configured container checks passed
     * @param json sanitized report
     * @param html static escaped report
     */
    public record Result(boolean passed, String json, String html) { }

    /**
     * Writes opt-in settings only; does not create a session, launch tests or contact Docker.
     * @param settings trusted configuration
     * @param config destination outside the session directory and counter trees
     * @throws IOException if configuration cannot be written
     */
    public static void prepare(Settings settings, Path config) throws IOException {
        Path absolute = config.toAbsolutePath().normalize();
        if (absolute.startsWith(settings.directory()) || settings.directory().startsWith(absolute)) throw problem("CONFIG_ALIASES_SESSION");
        rejectCounterOverlap(absolute, settings.jobCgroup());
        rejectCounterOverlap(absolute, settings.dindCgroup());
        DiagnosticsFiles.write(absolute, settings.json());
    }

    /**
     * Loads bounded versioned settings. No shell expansion or CI-environment inference is performed.
     * @param config independently retained configuration
     * @return validated settings
     * @throws IOException if the document cannot be read
     */
    public static Settings readSettings(Path config) throws IOException {
        try {
            var map = DiagnosticsFiles.json(config);
            if (!map.keySet().equals(Set.of("schemaVersion", "identity", "directory", "timeoutMillis", "sampleIntervalMillis", "maxSamples", "jobCgroup", "dindCgroup", "hostMemory", "dockerCommand", "dockerHost", "dedicatedDocker")) || number(map.get("schemaVersion")) != 1) throw problem("INVALID_CONFIGURATION");
            var identity = map(map.get("identity"));
            if (!identity.keySet().equals(Set.of("revision", "runId", "attempt", "suite", "node"))) throw problem("INVALID_CONFIGURATION");
            var command = new ArrayList<String>();
            if (!(map.get("dockerCommand") instanceof List<?> values)) throw problem("INVALID_CONFIGURATION");
            for (Object value : values) command.add(string(value));
            return new Settings(new Identity(string(identity.get("revision")), string(identity.get("runId")), Math.toIntExact(number(identity.get("attempt"))), string(identity.get("suite")), string(identity.get("node"))),
                    Path.of(string(map.get("directory"))), number(map.get("timeoutMillis")), number(map.get("sampleIntervalMillis")), Math.toIntExact(number(map.get("maxSamples"))),
                    path(map.get("jobCgroup")), path(map.get("dindCgroup")), bool(map.get("hostMemory")), command, string(map.get("dockerHost")), bool(map.get("dedicatedDocker")));
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw problem("INVALID_CONFIGURATION"); }
    }

    /**
     * Supervises an explicitly supplied command. Call outside the Gradle/Maven invocation being observed.
     * Stdout/stderr stay with CI; they are not copied into diagnostics. A session directory is single-use.
     * @param settings trusted configuration
     * @param command native argument vector, without a shell
     * @return native nonzero exit, 124 for timeout, 130 for interruption, or 1 for a failed diagnostics gate
     * @throws IOException if retained evidence cannot be written
     */
    public static int run(Settings settings, List<String> command) throws IOException {
        if (command.isEmpty() || command.size() > 256 || command.stream().anyMatch(value -> value.length() > 16_384 || value.indexOf('\0') >= 0)) throw problem("INVALID_COMMAND");
        Path directory = settings.directory(); DiagnosticsFiles.safe(directory);
        Files.createDirectories(directory.getParent());
        // CREATE_NEW semantics prevent old reports or another attempt from becoming this run's evidence.
        Files.createDirectory(directory);
        String fingerprint = fingerprint(settings);
        var initialDocker = DiagnosticDocker.snapshot(settings);
        var initialMemory = memory(settings, List.of());
        write(directory, "started.json", object("schemaVersion", 1, "configSha256", fingerprint, "identity", settings.identity().json(),
                "at", Instant.now().toString(), "docker", initialDocker, "memory", initialMemory));
        if (initialDockerCheckFailed(initialDocker)) {
            writeProcess(settings, "not_started", null, 0, false);
            finish(settings); return 1;
        }
        long started = System.nanoTime();
        Process process;
        try { process = new ProcessBuilder(List.copyOf(command)).inheritIO().start(); }
        catch (IOException failure) { writeProcess(settings, "start_failed", null, elapsed(started), false); finish(settings); return 1; }
        // This helps graceful shutdown only. SIGKILL leaves no final result, and the independent
        // verifier must report incomplete execution rather than infer success from earlier samples.
        Thread shutdown = new Thread(() -> {
            terminate(process);
            try { writeProcess(settings, "interrupted", process.isAlive() ? null : process.exitValue(), elapsed(started), process.isAlive()); }
            catch (IOException | IllegalArgumentException ignored) { /* after_script will mark missing evidence incomplete. */ }
        }, "shardpilot-diagnostics-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        String state = "completed"; boolean interrupted = false; List<ProcessHandle> observed = List.of(process.toHandle());
        try (var samples = Files.newBufferedWriter(directory.resolve("samples.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            int count = 0; long next = 0;
            while (true) {
                if (process.isAlive()) {
                    try (var descendants = process.descendants()) { observed = descendants.limit(1024).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
                    if (observed.size() >= 1024) throw problem("PROCESS_LIMIT");
                    observed.add(process.toHandle());
                }
                long millis = elapsed(started);
                // Hitting the retention cap stops sampling, not supervision or deadline enforcement.
                if (count < settings.maxSamples() && millis >= next) {
                    samples.write(EvidenceJson.write(object("elapsedMillis", millis, "memory", memory(settings, observed))) + "\n");
                    samples.flush(); count++; next = millis + settings.sampleIntervalMillis();
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break;
                if (settings.timeoutMillis() > 0 && elapsed(started) >= settings.timeoutMillis()) { state = "timeout"; terminate(process); break; }
            }
        } catch (InterruptedException cancelled) {
            interrupted = true; state = "interrupted"; terminate(process);
        } catch (IOException | IllegalArgumentException failure) {
            state = "observer_error"; terminate(process);
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* Shutdown evidence is intentionally conservative. */ }
        }
        Integer exit = process.isAlive() ? null : process.exitValue();
        // Process.waitFor is authoritative for the direct child. A separately queried Windows
        // ProcessHandle can briefly outlive its completion notification; only inspect descendants here.
        boolean remaining = process.isAlive() || observed.stream().filter(handle -> handle.pid() != process.pid()).anyMatch(ProcessHandle::isAlive);
        writeProcess(settings, state, exit, elapsed(started), remaining);
        Result result = finish(settings);
        if (interrupted) Thread.currentThread().interrupt();
        // Timeout/interruption have explicit codes. Otherwise preserve native failure even when
        // the independent diagnostics gate also fails; a successful XML file cannot override it.
        if (state.equals("timeout")) return 124;
        if (state.equals("interrupted")) return 130;
        if (exit != null && exit != 0) return exit;
        return result.passed() ? 0 : 1;
    }

    /**
     * Rechecks Docker and all retained files. Missing process evidence after SIGKILL remains incomplete.
     * Can be invoked independently from after_script; does not launch or repair the native command.
     * @param settings independently supplied expected configuration
     * @return one sanitized evaluation, also written as report.json and report.html
     * @throws IOException if reports cannot be written
     */
    public static Result finish(Settings settings) throws IOException {
        Path directory = settings.directory(); DiagnosticsFiles.safe(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw problem("SESSION_MISSING");
        // A repeated check must revoke the previous verdict before reading potentially damaged input.
        // JSON is published last and is the completion marker for this evaluation's report pair.
        for (String file : List.of("report.json", "report.html")) {
            Path output = directory.resolve(file); DiagnosticsFiles.safe(output);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) throw problem("UNSAFE_PATH");
            // Revoke JSON first even if the later HTML path is unsafe/unwritable. Never unlink an
            // unsafe entry merely to make publication succeed; the caller still receives a failure.
            Files.deleteIfExists(output);
        }
        var reasons = new ArrayList<String>();
        Map<?, ?> initial = Map.of();
        Map<?, ?> process;
        try { initial = DiagnosticsFiles.json(directory.resolve("started.json")); bind(settings, initial); }
        catch (IOException | IllegalArgumentException invalid) { reasons.add("INITIAL_EVIDENCE_INVALID"); }
        try {
            process = DiagnosticsFiles.json(directory.resolve("process.json")); bind(settings, process);
            if (!Set.of("completed", "timeout", "interrupted", "start_failed", "not_started", "observer_error").contains(string(process.get("state")))) throw problem("INVALID_DOCUMENT");
            if (process.get("exitCode") != null) {
                long exitCode = number(process.get("exitCode"));
                if (exitCode < Integer.MIN_VALUE || exitCode > Integer.MAX_VALUE) throw problem("INVALID_DOCUMENT");
            }
            if (number(process.get("elapsedMillis")) < 0) throw problem("INVALID_DOCUMENT");
            if (!Boolean.FALSE.equals(process.get("descendantsAlive"))) reasons.add("DESCENDANTS_REMAIN");
            if (!process.get("state").equals("completed")) reasons.add("PROCESS_" + string(process.get("state")).toUpperCase(java.util.Locale.ROOT));
            if (process.get("exitCode") == null || number(process.get("exitCode")) != 0) reasons.add("NATIVE_PROCESS_FAILED");
        } catch (IOException | IllegalArgumentException | ArithmeticException invalid) { process = object("state", "incomplete", "exitCode", null); reasons.add("PROCESS_RESULT_MISSING_OR_INVALID"); }
        var samples = samples(settings);
        if (Boolean.TRUE.equals(samples.get("partial"))) reasons.add("SAMPLES_INCOMPLETE");
        // Always query again: an earlier successful report says nothing about current daemon state.
        var finalDocker = DiagnosticDocker.snapshot(settings);
        Object firstDocker = initial.get("docker");
        if (initialDockerCheckFailed(firstDocker)) reasons.add("DOCKER_INITIAL_NOT_CLEAN");
        if (!settings.dockerCommand().isEmpty()) {
            if (!"available".equals(finalDocker.get("state"))) reasons.add("DOCKER_FINAL_UNAVAILABLE");
            else if (!(firstDocker instanceof Map<?, ?> first) || !finalDocker.get("daemonSha256").equals(first.get("daemonSha256"))) reasons.add("DOCKER_DAEMON_CHANGED");
            if (finalDocker.get("containers") instanceof List<?> ids && !ids.isEmpty()) reasons.add("CONTAINERS_REMAIN");
        }
        var finalMemory = memory(settings, List.of());
        Map<?, ?> initialMemory = initial.get("memory") instanceof Map<?, ?> value ? value : Map.of();
        var oom = object("job", oomDelta(initialMemory.get("job"), finalMemory.get("job")), "dind", oomDelta(initialMemory.get("dind"), finalMemory.get("dind")));
        boolean passed = reasons.isEmpty();
        // Only allowlisted process fields leave private state files. Never copy arbitrary producer keys.
        var publicProcess = object("state", process.get("state"), "exitCode", process.get("exitCode"), "elapsedMillis", process.get("elapsedMillis"));
        String json = EvidenceJson.write(object("schemaVersion", 1, "identity", settings.identity().json(), "configSha256", fingerprint(settings),
                "status", passed ? "passed" : "failed", "process", publicProcess, "samples", samples, "oomKillsDelta", oom,
                "finalMemory", finalMemory, "containers", object("initial", publicDocker(firstDocker), "final", publicDocker(finalDocker)), "reasons", reasons));
        String html = html(passed, publicProcess, samples, finalDocker, oom, reasons, json);
        write(directory, "report.html", html); write(directory, "report.json", json);
        return new Result(passed, json, html);
    }

    private static String html(boolean passed, Map<String, Object> process, Map<String, Object> samples,
                               Map<String, Object> docker, Map<String, Object> oom, List<String> reasons, String json) {
        var output = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>ShardPilot execution diagnostics</title><style>body{font:1rem/1.5 system-ui,sans-serif;margin:2rem auto;padding:0 1rem;max-width:75rem}"
                + "table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:.5rem;text-align:left}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body>");
        output.append("<h1>Execution diagnostics: ").append(passed ? "passed" : "failed").append("</h1><h2>Native process</h2><dl>");
        for (String field : List.of("state", "exitCode", "elapsedMillis")) output.append("<dt>").append(field).append("</dt><dd>")
                .append(DiagnosticsFiles.escape(String.valueOf(process.getOrDefault(field, "unknown")))).append("</dd>");
        output.append("</dl><h2>Findings</h2><ul>");
        if (reasons.isEmpty()) output.append("<li>No failed diagnostic checks.</li>");
        for (String reason : reasons) output.append("<li><code>").append(DiagnosticsFiles.escape(reason)).append("</code></li>");
        output.append("</ul><h2>Memory samples</h2><p>Exit 137 alone does not establish OOM. Unknown measurements are not zero. "
                + "Peaks are sampled; process RSS can count shared pages more than once.</p><table><thead><tr><th>Scope</th><th>Peak bytes</th><th>OOM kills delta</th></tr></thead><tbody>");
        Map<?, ?> peaks = map(samples.get("peakBytes"));
        for (String scope : List.of("process", "job", "dind", "host")) {
            output.append("<tr><th scope=\"row\">").append(scope).append("</th><td>")
                    .append(peaks.containsKey(scope) ? peaks.get(scope) : "unavailable").append("</td><td>")
                    .append(oom.get(scope) == null ? "unknown" : oom.get(scope)).append("</td></tr>");
        }
        output.append("</tbody></table><h2>Final container check</h2><p>").append(DiagnosticsFiles.escape(String.valueOf(docker.get("state"))))
                .append(". No containers are removed.</p><details><summary>Complete JSON evaluation</summary><pre>")
                .append(DiagnosticsFiles.escape(json)).append("</pre></details></body></html>");
        return output.toString();
    }

    private static Map<String, Object> publicDocker(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> source)) return object("state", "unavailable");
        String state = source.get("state") instanceof String text && Set.of("available", "unavailable", "disabled").contains(text) ? text : "unavailable";
        if (!state.equals("available")) return object("state", state);
        if (!(source.get("containers") instanceof List<?> ids) || ids.size() > 256 || ids.stream().anyMatch(id -> !(id instanceof String text) || !text.matches("[a-f0-9]{64}"))) return object("state", "unavailable");
        if (!(source.get("daemonSha256") instanceof String hash) || !hash.matches("[a-f0-9]{64}")) return object("state", "unavailable");
        return object("state", state, "daemonSha256", hash, "containers", ids);
    }

    private static boolean initialDockerCheckFailed(@Nullable Object snapshot) {
        var value = publicDocker(snapshot);
        if ("disabled".equals(value.get("state"))) return false;
        return !"available".equals(value.get("state")) || !List.of().equals(value.get("containers"));
    }

    private static Map<String, Object> memory(Settings settings, List<ProcessHandle> processes) {
        return object("process", DiagnosticMemory.process(processes), "job", DiagnosticMemory.cgroup(settings.jobCgroup()),
                "dind", DiagnosticMemory.cgroup(settings.dindCgroup()), "host", DiagnosticMemory.host(settings.hostMemory()));
    }

    private static @Nullable Long oomDelta(@Nullable Object first, @Nullable Object last) {
        // Counter resets/version changes are incomparable. Even a valid increase identifies the
        // declared cgroup, not the particular process/test killed within that cgroup.
        try {
            if (first instanceof Map<?, ?> a && last instanceof Map<?, ?> b
                    && "available".equals(a.get("state")) && "available".equals(b.get("state"))
                    && a.get("version") instanceof Number av && b.get("version") instanceof Number bv && number(av) == number(bv)
                    && a.get("oomKills") instanceof Number x && b.get("oomKills") instanceof Number y) {
                long before = number(x), after = number(y);
                if (before >= 0 && after >= before) return after - before;
            }
        } catch (ArithmeticException invalid) { /* Counter resets/unavailable values do not establish OOM. */ }
        return null;
    }

    private static Map<String, Object> samples(Settings settings) {
        Path file = settings.directory().resolve("samples.jsonl"); int count = 0; boolean partial = false;
        var peaks = new LinkedHashMap<String, Long>(); var unavailable = new LinkedHashMap<String, Integer>();
        try {
            byte[] bytes = DiagnosticsFiles.read(file, 33_554_432);
            String text = EvidenceFiles.text(bytes);
            // A killed writer can leave a final partial row. Preserve prior observations for diagnosis,
            // but never accept that partial stream as a successful completed collection.
            if (!text.isEmpty() && !text.endsWith("\n")) partial = true;
            String[] lines = text.split("\n", -1);
            long previous = -1;
            for (int i = 0; i < lines.length - 1; i++) {
                if (++count > settings.maxSamples() || lines[i].length() > 65_536) throw problem("RESOURCE_LIMIT");
                Map<?, ?> sample = map(EvidenceJson.parse(lines[i], 10_000));
                long elapsed = number(sample.get("elapsedMillis"));
                if (elapsed < previous || elapsed < 0) throw problem("INVALID_SAMPLE"); previous = elapsed;
                Map<?, ?> memory = map(sample.get("memory"));
                for (String scope : List.of("process", "job", "dind", "host")) {
                    Map<?, ?> source = map(memory.get(scope));
                    if ("available".equals(source.get("state"))) {
                        long used = number(source.get("usedBytes")); if (used < 0) throw problem("INVALID_SAMPLE");
                        peaks.merge(scope, used, Math::max);
                    } else unavailable.merge(scope, 1, Integer::sum);
                }
            }
            if (count == 0) partial = true;
        } catch (IOException | IllegalArgumentException | ArithmeticException invalid) { partial = true; }
        return object("count", count, "partial", partial, "limitReached", count >= settings.maxSamples(), "peakBytes", peaks, "unavailableSamples", unavailable);
    }

    private static void writeProcess(Settings settings, String state, @Nullable Integer exit, long elapsed, boolean descendantsAlive) throws IOException {
        write(settings.directory(), "process.json", object("schemaVersion", 1, "configSha256", fingerprint(settings), "identity", settings.identity().json(),
                "state", state, "exitCode", exit, "elapsedMillis", elapsed, "descendantsAlive", descendantsAlive));
    }
    private static void bind(Settings settings, Map<?, ?> document) {
        if (number(document.get("schemaVersion")) != 1 || !fingerprint(settings).equals(document.get("configSha256"))
                || !EvidenceJson.write(settings.identity().json()).equals(EvidenceJson.write(document.get("identity")))) throw problem("IDENTITY_MISMATCH");
    }
    private static String fingerprint(Settings settings) { return EvidenceFiles.digest(settings.json().getBytes(StandardCharsets.UTF_8)); }
    private static void write(Path directory, String file, Object value) throws IOException { DiagnosticsFiles.write(directory.resolve(file), value instanceof String text ? text : EvidenceJson.write(value)); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void terminate(Process process) {
        // Best-effort termination of this invocation's process tree only; no host-process scan and
        // no Docker cleanup. Detached descendants require containment by the owning CI runtime.
        boolean interrupted = Thread.interrupted();
        try (var children = process.descendants()) {
            List<ProcessHandle> handles = children.limit(1024).toList();
            handles.forEach(ProcessHandle::destroy); process.destroy();
            try { process.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException cancelled) { interrupted = true; }
            handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException cancelled) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void rejectCounterOverlap(Path destination, @Nullable Path source) {
        if (source != null && (destination.startsWith(source) || source.startsWith(destination))) throw problem("OUTPUT_ALIASES_COUNTERS");
    }
    private static boolean invalidIdentifier(String value) { return !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"); }
    private static Map<?, ?> map(@Nullable Object value) { if (value instanceof Map<?, ?> map) return map; throw problem("INVALID_DOCUMENT"); }
    private static String string(@Nullable Object value) { if (value instanceof String text) return text; throw problem("INVALID_DOCUMENT"); }
    private static boolean bool(@Nullable Object value) { if (value instanceof Boolean bool) return bool; throw problem("INVALID_DOCUMENT"); }
    private static long number(@Nullable Object value) { if (value instanceof BigDecimal decimal) return decimal.longValueExact(); if (value instanceof Number number) return number.longValue(); throw problem("INVALID_DOCUMENT"); }
    private static @Nullable Path path(@Nullable Object value) { return value == null ? null : Path.of(string(value)); }
    static Problem problem(String code) { return new Problem(code); }
    /** Fixed diagnostic codes never carry filesystem, Docker or native-command error payloads. */
    public static final class Problem extends IllegalArgumentException {
        @Serial private static final long serialVersionUID = 1L;
        private Problem(String code) { super("C4 " + code); }
    }
}
