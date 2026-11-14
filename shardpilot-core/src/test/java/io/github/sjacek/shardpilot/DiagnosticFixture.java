package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;

/** Controlled native processes and a read-only Docker CLI double; no application or shared daemon is used. */
public final class DiagnosticFixture {
    private DiagnosticFixture() { }
    /** @param args fixture operation and private temporary paths */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("docker")) {
            Path root = Path.of(args[1]);
            Files.writeString(root.resolve("queries.txt"), String.join(" ", java.util.Arrays.asList(args).subList(2, args.length)) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            if (!args[2].equals("--host") || !java.util.Set.of("info", "ps").contains(args[4])) throw new IllegalStateException("Unexpected Docker operation");
            if (Files.exists(root.resolve("delay"))) {
                // The query must terminate this owned fixture on timeout/interruption, not wait for EOF.
                Files.writeString(root.resolve("docker-pid"), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30000);
            }
            if (Files.exists(root.resolve("unavailable"))) { System.err.println("PRIVATE_DOCKER_ERROR"); System.exit(2); }
            if (args[4].equals("info")) System.out.println(Files.exists(root.resolve("daemon")) ? Files.readString(root.resolve("daemon")) : "daemon-testdata");
            else if (Files.exists(root.resolve("containers"))) System.out.write(Files.readAllBytes(root.resolve("containers")));
            return;
        }
        if (args[0].equals("exit")) { System.exit(Integer.parseInt(args[1])); return; }
        Path root = Path.of(args[1]);
        Files.writeString(root.resolve("native-marker"), Long.toString(ProcessHandle.current().pid()));
        switch (args[0]) {
            case "wait" -> Thread.sleep(Long.parseLong(args[2]));
            case "arguments" -> Files.writeString(root.resolve("arguments.txt"), String.join("\n", java.util.Arrays.asList(args).subList(2, args.length)));
            case "leak" -> Files.writeString(root.resolve("containers"), "a".repeat(64) + "\n");
            case "swap" -> Files.writeString(root.resolve("daemon"), "other-daemon-testdata");
            case "unavailable" -> Files.writeString(root.resolve("unavailable"), "unavailable");
            case "oom" -> { Files.writeString(root.resolve("memory.events"), "oom_kill 6\n"); System.exit(137); }
            default -> throw new IllegalArgumentException("Unknown fixture operation");
        }
    }
}
