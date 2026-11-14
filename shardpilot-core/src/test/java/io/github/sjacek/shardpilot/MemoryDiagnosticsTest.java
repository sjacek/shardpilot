package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Partially readable cgroups must keep valid measurements without inventing optional OOM data. */
class MemoryDiagnosticsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"1,missing", "2,missing", "1,duplicate", "2,duplicate", "1,negative", "2,negative", "1,overflow", "2,overflow"})
    void anInvalidOptionalOomCounterDoesNotDiscardValidUsageAndLimit(int version, String scenario) throws Exception {
        Files.writeString(directory.resolve(version == 2 ? "memory.current" : "memory.usage_in_bytes"), "1234");
        Files.writeString(directory.resolve(version == 2 ? "memory.max" : "memory.limit_in_bytes"), "4096");
        String value = switch (scenario) {
            case "duplicate" -> "oom_kill 1\noom_kill 1\n";
            case "negative" -> "oom_kill -1\n";
            case "overflow" -> "oom_kill 9223372036854775808\n";
            default -> null;
        };
        if (value != null) Files.writeString(directory.resolve(version == 2 ? "memory.events" : "memory.oom_control"), value);
        var result = DiagnosticMemory.cgroup(directory);
        assertEquals("available", result.get("state"));
        assertEquals(1234L, result.get("usedBytes")); assertEquals(4096L, result.get("limitBytes"));
        assertNull(result.get("oomKills"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void aMeasuredZeroIsPreservedAsDataRatherThanTreatedAsUnavailable(int version) throws Exception {
        Files.writeString(directory.resolve(version == 2 ? "memory.current" : "memory.usage_in_bytes"), "0");
        Files.writeString(directory.resolve(version == 2 ? "memory.max" : "memory.limit_in_bytes"), "4096");
        Files.writeString(directory.resolve(version == 2 ? "memory.events" : "memory.oom_control"), "oom_kill 0\n");
        var result = DiagnosticMemory.cgroup(directory);
        assertEquals("available", result.get("state")); assertEquals(0L, result.get("usedBytes")); assertEquals(0L, result.get("oomKills"));
        assertEquals("unavailable", DiagnosticMemory.cgroup(null).get("state"));
        assertFalse(DiagnosticMemory.cgroup(null).containsKey("usedBytes"));
    }
}
