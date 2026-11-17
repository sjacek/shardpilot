package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** JDK-only external supervisor, bundled in both distributions; no shell helper or telemetry. */
public final class DiagnosticsCli {
    private DiagnosticsCli() { }
    /**
     * Runs {@code run CONFIG -- COMMAND ARGS...} or {@code finish CONFIG}.
     * @param arguments explicit local configuration and native argument vector
     */
    public static void main(String[] arguments) { System.exit(execute(arguments)); }

    /** Exit-code boundary, separated from System.exit so invalid invocations can be checked in process. */
    static int execute(String[] arguments) {
        try {
            if (arguments.length < 2) throw Diagnostics.problem("INVALID_CLI");
            var settings = Diagnostics.readSettings(Path.of(arguments[1]));
            if (arguments[0].equals("finish") && arguments.length == 2) return Diagnostics.finish(settings).passed() ? 0 : 1;
            if (arguments[0].equals("run") && arguments.length >= 4 && arguments[2].equals("--")) return Diagnostics.run(settings, Arrays.asList(arguments).subList(3, arguments.length));
            throw Diagnostics.problem("INVALID_CLI");
        } catch (IOException | IllegalArgumentException failure) {
            // Filesystem/decoder messages may contain private paths or input. Only our fixed codes
            // are safe to publish; never print an arbitrary exception or its cause chain here.
            System.err.println(failure instanceof Diagnostics.Problem ? failure.getMessage() : "C4 DIAGNOSTICS_UNAVAILABLE");
            return 1;
        }
    }
}
