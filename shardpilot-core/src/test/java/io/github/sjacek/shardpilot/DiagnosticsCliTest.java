package io.github.sjacek.shardpilot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.sjacek.shardpilot.DiagnosticsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** External CLI grammar, private failures and literal forwarding without global System.exit. */
@ResourceLock(Resources.SYSTEM_ERR)
class DiagnosticsCliTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"empty", "missing-config", "unknown-operation", "missing-command", "missing-separator", "extra-finish-argument"})
    void invalidGrammarNeverLaunchesAProcessOrEchoesArguments(String scenario) throws Exception {
        Path config = prepare();
        String[] args = switch (scenario) {
            case "empty" -> new String[0];
            case "missing-config" -> new String[] {"run"};
            case "unknown-operation" -> new String[] {"PRIVATE_OPERATION", config.toString()};
            case "missing-command" -> new String[] {"run", config.toString(), "--"};
            case "missing-separator" -> new String[] {"run", config.toString(), "PRIVATE_SEPARATOR", "PRIVATE_COMMAND"};
            default -> new String[] {"finish", config.toString(), "PRIVATE_EXTRA"};
        };
        assertEquals("C4 INVALID_CLI", error(args));
        assertFalse(Files.exists(directory.resolve("session")));
    }

    @Test void anAbsentSessionAndDamagedConfigurationFailWithFixedCodes() throws Exception {
        Path config = prepare();
        assertEquals("C4 SESSION_MISSING", error("finish", config.toString()));
        Files.writeString(config, "PRIVATE_INVALID_DOCUMENT");
        assertEquals("C4 INVALID_CONFIGURATION", error("finish", config.toString()));
        assertFalse(Files.exists(directory.resolve("session")));
    }

    @Test void preservesNativeFailureAndRejectsItAgainInAnIndependentFinishInvocation() throws Exception {
        Path config = prepare();
        var args = new ArrayList<>(List.of("run", config.toString(), "--")); args.addAll(nativeCommand("exit", "7"));
        assertEquals(7, DiagnosticsCli.execute(args.toArray(String[]::new)));
        assertEquals(1, DiagnosticsCli.execute(new String[] {"finish", config.toString()}));
        assertTrue(Files.readString(directory.resolve("session/report.json")).contains("NATIVE_PROCESS_FAILED"));
    }

    @Test void forwardsSpacesUnicodeAndShellMetacharactersAsLiteralArguments() throws Exception {
        Path config = prepare();
        // Native argument quoting remains platform-specific; no shell should interpret these values.
        var literals = List.of("PRIVATE value with spaces", "PRIVATE_żółć", "; PRIVATE_$HOME $(PRIVATE_COMMAND) & | %PRIVATE%");
        var command = new ArrayList<>(nativeCommand("arguments", directory.toString())); command.addAll(literals);
        var args = new ArrayList<>(List.of("run", config.toString(), "--")); args.addAll(command);
        assertEquals(0, DiagnosticsCli.execute(args.toArray(String[]::new)));
        assertEquals(String.join("\n", literals), Files.readString(directory.resolve("arguments.txt")));
        assertEquals(0, DiagnosticsCli.execute(new String[] {"finish", config.toString()}));
        assertFalse(Files.readString(directory.resolve("session/report.json")).contains("PRIVATE"));
    }

    private Path prepare() throws Exception {
        Path config = directory.resolve("PRIVATE-settings.json");
        Diagnostics.prepare(settings(directory.resolve("session")), config); return config;
    }

    private static String error(String... args) {
        var bytes = new ByteArrayOutputStream(); PrintStream previous = System.err;
        // ResourceLock serializes other JUnit tests using stderr; restore even when assertions fail.
        try (var captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(captured); assertEquals(1, DiagnosticsCli.execute(args));
        } finally { System.setErr(previous); }
        return bytes.toString(StandardCharsets.UTF_8).strip();
    }
}
