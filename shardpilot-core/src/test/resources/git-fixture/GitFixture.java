import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

/**
 * Portable source-launcher fixture shared by core, TestKit and native Maven tests.
 * Writes synthetic loose Git objects in a marked test directory. Never runs commit/push,
 * uses personal identities or modifies the developer checkout's history.
 */
class GitFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        if (Files.exists(root.resolve(".git")) || !Files.readString(root.resolve(".shardpilot-fixture-testdata")).equals("synthetic Git fixture\n")) {
            throw new IllegalArgumentException("A new marked fixture directory is required");
        }
        Files.writeString(root.resolve(".gitignore"), "build/\ntarget/\n.gradle/\nbuild.log\n");
        Files.writeString(root.resolve(".gitattributes"), "* -text\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/old name.txt"), "rename content\n");
        Files.writeString(root.resolve("src/modified.txt"), "before\n");
        Files.writeString(root.resolve("deleted.txt"), "deleted\n");
        String mode = args.length > 1 ? args[1] : "paths";
        boolean impact = mode.startsWith("impact");
        if (impact) {
            Files.createDirectories(root.resolve("src/test/java/example/selection"));
            Path alpha = root.resolve("src/test/java/example/selection/AlphaTest.java");
            if (!Files.exists(alpha)) Files.writeString(alpha, "package example.selection; class AlphaTest {}\n");
            Files.createDirectories(root.resolve("src/library")); Files.writeString(root.resolve("src/library/Api.txt"), "before\n");
            Files.createDirectories(root.resolve("contracts"));
            Files.writeString(root.resolve("contracts/service.json"), "{\"paths\":{\"/payments\":{\"summary\":\"before\"}},\"metadata\":1}\n");
            Files.writeString(root.resolve("contracts/service.yaml"), "paths:\n  /payments:\n    summary: before\nmetadata: 1\n");
            impactExtras(root, mode, 0);
        }
        // The object writer below uses Git's SHA-1 object format, independently of global Git settings.
        git(root, "init", "--quiet", "--object-format=sha1");
        String baseTree = tree(root, root);
        String base = commit(root, baseTree);
        Path old = root.resolve("src/old name.txt").toRealPath();
        Path renamed = root.resolve("src/new name.txt").toAbsolutePath().normalize();
        if (!old.startsWith(root) || !renamed.startsWith(root)) throw new IllegalArgumentException("Fixture move escaped its root");
        Files.move(old, renamed);
        Files.writeString(root.resolve("src/modified.txt"), "after\n");
        String middle = commit(root, tree(root, root), base);
        Files.writeString(root.resolve("src/addition.txt"), "added\n");
        Files.delete(root.resolve("deleted.txt"));
        if (impact) {
            Files.writeString(root.resolve("src/test/java/example/selection/AlphaTest.java"), "// Directly changed eligible test\n", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(root.resolve("src/library/Api.txt"), "after\n");
            Files.writeString(root.resolve("contracts/service.json"), "{\"paths\":{\"/payments\":{\"summary\":\"after\"}},\"metadata\":1}\n");
            Files.writeString(root.resolve("contracts/service.yaml"), "paths:\n  /payments:\n    summary: after\nmetadata: 1\n");
            if (mode.equals("impact-invalid")) Files.writeString(root.resolve("contracts/service.json"), "{PRIVATE_INVALID_INPUT");
            if (mode.equals("impact-oversized")) Files.write(root.resolve("contracts/service.json"), new byte[1_048_577]);
            impactExtras(root, mode, 1);
        }
        String headTree = tree(root, root);
        String head = commit(root, headTree, middle);
        // A divergent target and a synthetic merge exercise ancestry without changing the worktree.
        String target = commit(root, baseTree, base);
        String merged = commit(root, headTree, head, target);
        String ambiguousLeft = commit(root, headTree, middle, target);
        String ambiguousRight = commit(root, baseTree, target, middle);
        String unrelated = commit(root, headTree);
        Files.createDirectories(root.resolve(".git/refs/heads"));
        Files.writeString(root.resolve(".git/refs/heads/main-testdata"), base + "\n");
        Files.writeString(root.resolve(".git/refs/heads/feature-testdata"), head + "\n");
        Files.writeString(root.resolve(".git/refs/heads/merged-testdata"), merged + "\n");
        Files.writeString(root.resolve(".git/refs/heads/ambiguous-left-testdata"), ambiguousLeft + "\n");
        Files.writeString(root.resolve(".git/refs/heads/ambiguous-right-testdata"), ambiguousRight + "\n");
        Files.writeString(root.resolve(".git/refs/heads/unrelated-testdata"), unrelated + "\n");
        Files.createDirectories(root.resolve(".git/refs/remotes/origin"));
        Files.writeString(root.resolve(".git/refs/remotes/origin/main-testdata"), target + "\n");
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/feature-testdata\n");
        git(root, "read-tree", "HEAD");
        System.out.println(base);
        System.out.println(head);
    }

    /** Each budget blob is valid and exactly half a MiB; eight before/after pairs exhaust eight MiB. */
    private static void impactExtras(Path root, String mode, int value) throws Exception {
        int count = switch (mode) {
            case "impact-count-32" -> 30;
            case "impact-count-33" -> 31;
            case "impact-budget" -> 9;
            default -> 0;
        };
        String start = "{\"value\":" + value + ",\"padding\":\"", end = "\"}";
        String padding = mode.equals("impact-budget") ? "x".repeat(524_288 - start.length() - end.length()) : "";
        for (int index = 0; index < count; index++) {
            Files.writeString(root.resolve("contracts/batch-%02d.json".formatted(index)), start + padding + end);
        }
    }

    private static String commit(Path root, String tree, String... parents) throws Exception {
        String text = "tree " + tree + "\n" + String.join("", java.util.Arrays.stream(parents).map(parent -> "parent " + parent + "\n").toList())
                + "author Fixture <fixture@example.invalid> 1700000000 +0000\n"
                + "committer Fixture <fixture@example.invalid> 1700000000 +0000\n\nSynthetic test history\n";
        return object(root, "commit", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String tree(Path root, Path directory) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.filter(path -> !Set.of(".git", "build", "target", ".gradle", "build.log").contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName() + (Files.isDirectory(path) ? "/" : ""))).toList()) {
                if (Files.isSymbolicLink(entry)) throw new IllegalArgumentException("Fixture symlinks are not supported");
                boolean folder = Files.isDirectory(entry);
                String id = folder ? tree(root, entry) : object(root, "blob", Files.readAllBytes(entry));
                output.write(((folder ? "40000 " : "100644 ") + entry.getFileName() + "\0").getBytes(StandardCharsets.UTF_8));
                output.write(HexFormat.of().parseHex(id));
            }
        }
        return object(root, "tree", output.toByteArray());
    }

    private static String object(Path root, String type, byte[] contents) throws Exception {
        var output = new ByteArrayOutputStream();
        output.write((type + " " + contents.length + "\0").getBytes(StandardCharsets.UTF_8));
        output.write(contents);
        byte[] bytes = output.toByteArray();
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        Path file = root.resolve(".git/objects/" + id.substring(0, 2) + "/" + id.substring(2));
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) try (var compressed = new DeflaterOutputStream(Files.newOutputStream(file))) { compressed.write(bytes); }
        return id;
    }

    private static void git(Path root, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (process.waitFor() != 0) throw new IllegalStateException("Fixture Git setup failed");
    }
}
