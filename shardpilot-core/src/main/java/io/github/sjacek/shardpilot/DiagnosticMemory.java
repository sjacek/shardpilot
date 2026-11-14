package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.DiagnosticsFiles.object;

/** Allowlisted counters only; never reads command lines, environments, Docker logs or application output. */
final class DiagnosticMemory {
    private DiagnosticMemory() { }

    static Map<String, Object> unavailable(String reason) { return object("state", "unavailable", "reason", reason); }

    /** Reads one explicit cgroup; discovery must not silently substitute the host or another scope. */
    static Map<String, Object> cgroup(@Nullable Path directory) {
        if (directory == null) return unavailable("NOT_CONFIGURED");
        try {
            boolean v2 = Files.exists(directory.resolve("memory.current"));
            long current = counter(directory.resolve(v2 ? "memory.current" : "memory.usage_in_bytes"));
            String limitText = text(directory.resolve(v2 ? "memory.max" : "memory.limit_in_bytes")).strip();
            Long limit = limitText.equals("max") ? null : positive(limitText);
            // v1 commonly represents an unlimited limit by a page-aligned value near Long.MAX_VALUE.
            if (!v2 && limit != null && limit > Long.MAX_VALUE / 2) limit = null;
            Long kills = null;
            try { kills = field(directory.resolve(v2 ? "memory.events" : "memory.oom_control"), "oom_kill"); }
            catch (IOException | IllegalArgumentException | ArithmeticException missing) {
                // OOM is an optional counter. Invalid/missing OOM data must not discard valid usage/limit.
                // It also must not be converted to zero, which would invent a comparable baseline.
            }
            return object("state", "available", "version", v2 ? 2 : 1, "usedBytes", current, "limitBytes", limit, "oomKills", kills);
        } catch (IOException | IllegalArgumentException | ArithmeticException invalid) { return unavailable("COUNTER_UNAVAILABLE"); }
    }

    /** Aggregates only owned, visible processes; RSS is not unique physical memory or a JVM heap metric. */
    static Map<String, Object> process(List<ProcessHandle> processes) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) return unavailable("UNSUPPORTED_PLATFORM");
        long bytes = 0; int observed = 0;
        try {
            for (ProcessHandle process : processes) {
                if (!process.isAlive()) continue;
                Long rss = field(Path.of("/proc", Long.toString(process.pid()), "status"), "VmRSS:");
                if (rss == null) return unavailable("COUNTER_UNAVAILABLE");
                bytes = Math.addExact(bytes, Math.multiplyExact(rss, 1024)); observed++;
            }
            return observed == 0 ? unavailable("PROCESS_FINISHED") : object("state", "available", "usedBytes", bytes, "processes", observed);
        } catch (IOException | IllegalArgumentException | ArithmeticException missing) { return unavailable("COUNTER_UNAVAILABLE"); }
    }

    static Map<String, Object> host(boolean enabled) {
        if (!enabled) return unavailable("NOT_CONFIGURED");
        try {
            Long total = field(Path.of("/proc/meminfo"), "MemTotal:");
            Long available = field(Path.of("/proc/meminfo"), "MemAvailable:");
            if (total == null || available == null || available > total) return unavailable("COUNTER_UNAVAILABLE");
            return object("state", "available", "usedBytes", Math.multiplyExact(total - available, 1024), "limitBytes", Math.multiplyExact(total, 1024));
        } catch (IOException | IllegalArgumentException | ArithmeticException missing) { return unavailable("COUNTER_UNAVAILABLE"); }
    }

    /** A duplicated counter is ambiguous, even when its two values happen to agree. */
    static @Nullable Long field(Path file, String name) throws IOException {
        Long value = null;
        for (String line : text(file).split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length >= 2 && parts[0].equals(name)) {
                if (value != null) throw Diagnostics.problem("INVALID_COUNTER");
                value = positive(parts[1]);
            }
        }
        return value;
    }

    static long counter(Path file) throws IOException { return positive(text(file).strip()); }
    static String text(Path file) throws IOException { return EvidenceFiles.text(DiagnosticsFiles.read(file, 65_536)); }
    static long positive(String value) {
        if (!value.matches("[0-9]{1,19}")) throw Diagnostics.problem("INVALID_COUNTER");
        long result = new BigDecimal(value).longValueExact();
        if (result < 0) throw Diagnostics.problem("INVALID_COUNTER");
        return result;
    }
}
