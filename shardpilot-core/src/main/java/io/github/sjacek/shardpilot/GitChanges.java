package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NullMarked;

/**
 * Shared I/O support around the pure selection engine. Commands use argument arrays, NUL-delimited
 * names and bounded output/time; Git stderr and remote addresses are never included in diagnostics.
 */
@NullMarked
public final class GitChanges {
    private static final int OUTPUT_LIMIT = 8_388_608;

    private GitChanges() { }

    /** Provenance of the chosen base, independent of whether acquisition succeeded. */
    public enum Source {
        /** User-supplied revision. */ EXPLICIT,
        /** GitLab merge-request base. */ GITLAB_MERGE_REQUEST,
        /** GitLab push before-SHA. */ GITLAB_PUSH,
        /** GitHub pull-request payload or target ref. */ GITHUB_PULL_REQUEST,
        /** GitHub push before-SHA. */ GITHUB_PUSH,
        /** No supported base was available. */ UNAVAILABLE,
        /** The selected mode does not inspect changes. */ NOT_REQUIRED
    }

    /** Stable metadata-only acquisition outcomes; no arbitrary subprocess messages are retained. */
    public enum Failure {
        /** Successful acquisition, or acquisition was unnecessary. */ NONE,
        /** No nonzero base could be inferred. */ NO_BASE,
        /** The directory is not a readable Git checkout. */ NOT_REPOSITORY,
        /** A requested revision is unavailable locally. */ MISSING_REVISION,
        /** History is shallow, unrelated or has ambiguous common ancestors. */ NO_MERGE_BASE,
        /** Uncommitted work makes committed-only impact unsafe. */ DIRTY_WORKTREE,
        /** Git could not execute the requested read. */ COMMAND_FAILED,
        /** A command exceeded its timeout. */ TIMEOUT,
        /** A command exceeded bounded output. */ OUTPUT_LIMIT,
        /** CI metadata, paths or Git output violated the contract. */ INVALID_INPUT,
        /** Acquisition was interrupted; the caller's interrupt flag is preserved. */ INTERRUPTED
    }

    /**
     * Frozen change acquisition consumed by either adapter and passed between CI nodes.
     * @param changes complete normalized paths and exact revisions, or explicitly unavailable input
     * @param source base-revision source
     * @param failure stable outcome code
     */
    public record Result(SelectionContext.ChangeSet changes, Source source, Failure failure) {
        /** Enforces availability/outcome consistency without inventing empty successful diffs. */
        public Result {
            if (changes.availability() == SelectionContext.Availability.KNOWN && (failure != Failure.NONE
                    || source == Source.NOT_REQUIRED || source == Source.UNAVAILABLE
                    || changes.baseRevision().isEmpty() || changes.headRevision().isEmpty())
                    || changes.availability() == SelectionContext.Availability.UNKNOWN && failure == Failure.NONE && source != Source.NOT_REQUIRED) {
                throw new IllegalArgumentException("Invalid Git acquisition outcome");
            }
        }

        /**
         * Serializes only bounded metadata; JSON is also valid input to the strict snapshot reader.
         * @return deterministic version-1 acquisition snapshot
         */
        public String json() {
            return "{\"schemaVersion\":1,\"source\":" + ShardCoverage.quote(source.name()) + ",\"failure\":"
                    + ShardCoverage.quote(failure.name()) + ",\"availability\":" + ShardCoverage.quote(changes.availability().name())
                    + ",\"baseRevision\":" + ShardCoverage.quote(changes.baseRevision()) + ",\"headRevision\":"
                    + ShardCoverage.quote(changes.headRevision()) + ",\"paths\":["
                    + String.join(",", changes.paths().stream().map(ShardCoverage::quote).toList()) + "]}\n";
        }
    }

    /**
     * Acquires a complete committed diff. Dirty checkouts return UNKNOWN, allowing FULL/FAIL policy.
     * Explicit invalid parameters throw before invoking Git; missing objects never become an empty diff.
     * @param options validated subprocess parameters
     * @param environment CI hints; only documented keys are read
     * @return immutable observed changes with sanitized acquisition evidence
     */
    public static Result acquire(GitChangeOptions options, Map<String, String> environment) {
        Source source = options.base().isEmpty() ? Source.UNAVAILABLE : Source.EXPLICIT;
        String head = "";
        try {
            var base = options.base().isEmpty() ? infer(options, environment) : new Base(options.base(), Source.EXPLICIT);
            source = base.source();
            var git = new Commands(options);
            head = git.resolve("HEAD");
            if (head.isEmpty()) return unavailable(source, Failure.NOT_REPOSITORY, head);
            String requestedHead = git.resolve(options.head());
            if (requestedHead.isEmpty()) return unavailable(source, Failure.MISSING_REVISION, head);
            if (!head.equals(requestedHead)) throw new IllegalArgumentException("Selection Git head must match the checked-out HEAD");
            if (base.ref().isEmpty() || base.ref().matches("0+")) return unavailable(source, Failure.NO_BASE, head);
            try { GitChangeOptions.validateRevision(base.ref()); }
            catch (IllegalArgumentException invalid) { return unavailable(source, Failure.INVALID_INPUT, head); }
            if (git.checked("status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none").length != 0) {
                return unavailable(source, Failure.DIRTY_WORKTREE, head);
            }
            String resolvedBase = git.resolve(base.ref());
            String comparison = resolvedBase.isEmpty() ? "" : comparison(git, resolvedBase, head, options.mergeBase());
            // Absence is uncertainty, not an empty diff. Only explicit permission can acquire objects;
            // adapters remove that permission in offline mode before constructing these options.
            if ((resolvedBase.isEmpty() || comparison.isEmpty()) && options.fetch()) {
                String fetchRef = base.ref().replace("refs/remotes/" + options.remote() + "/", "refs/heads/");
                git.checked("fetch", "--no-tags", "--no-recurse-submodules", "--depth=" + options.fetchDepth(),
                        "--", options.remote(), fetchRef, head);
                resolvedBase = git.resolve(base.ref());
                comparison = resolvedBase.isEmpty() ? "" : comparison(git, resolvedBase, head, options.mergeBase());
            }
            if (resolvedBase.isEmpty()) return unavailable(source, Failure.MISSING_REVISION, head);
            if (comparison.isEmpty()) return unavailable(source, Failure.NO_MERGE_BASE, head);
            // Repository-relative names must not depend on -C or the user's diff.relative setting.
            // Disable external transformations: the engine classifies tree paths, not rendered content.
            var paths = names(git.checked("diff", "--ignore-submodules=none", "--no-relative", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "--find-renames", comparison, head, "--"));
            // A moving checkout cannot combine two different invocations' provenance in one snapshot.
            if (!head.equals(git.resolve("HEAD")) || git.checked("status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none").length != 0) {
                return unavailable(source, Failure.DIRTY_WORKTREE, head);
            }
            return new Result(new SelectionContext.ChangeSet(SelectionContext.Availability.KNOWN, paths, comparison, head), source, Failure.NONE);
        } catch (CommandFailure failure) {
            return unavailable(source, failure.failure, head);
        } catch (IOException failure) {
            return unavailable(source, Failure.INVALID_INPUT, head);
        }
    }

    /**
     * Reads only local HEAD, used to reject a frozen selection on another checkout. No diff/fetch is performed.
     * @param directory worker checkout
     * @return full local commit identity
     */
    public static String head(Path directory) {
        try {
            var head = new Commands(GitChangeOptions.defaults(directory)).resolve("HEAD");
            if (head.isEmpty()) throw new IllegalArgumentException("Cannot verify the frozen selection checkout");
            return head;
        } catch (CommandFailure failure) { throw new IllegalArgumentException("Cannot verify the frozen selection checkout"); }
    }

    /**
     * Validates local checkout identity without querying any mutable base ref or remote.
     * @param directory worker checkout
     * @param expectedHead frozen exact head identity
     * @param requireClean require committed-only inputs for a known affected selection
     */
    public static void verifyCheckout(Path directory, String expectedHead, boolean requireClean) {
        if (!head(directory).equals(expectedHead)) throw new IllegalArgumentException("Frozen selection belongs to another checkout");
        try {
            if (requireClean && new Commands(GitChangeOptions.defaults(directory))
                    .checked("status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none").length != 0) {
                throw new IllegalArgumentException("Frozen affected selection requires a clean checkout");
            }
        } catch (CommandFailure failure) { throw new IllegalArgumentException("Cannot verify the frozen selection checkout"); }
    }

    /**
     * Represents a full/smoke/explicit profile without touching Git or requiring a checkout.
     * @return unavailable changes deliberately unused by the selected profile
     */
    public static Result notRequired() {
        return new Result(SelectionContext.ChangeSet.unknown(), Source.NOT_REQUIRED, Failure.NONE);
    }

    /**
     * Reads one strict bounded snapshot; corrupt artifacts fail instead of requesting full fallback.
     * @param file prepared acquisition artifact
     * @return validated immutable metadata
     * @throws IOException if the artifact cannot be read
     */
    public static Result read(Path file) throws IOException {
        Object parsed;
        try {
            parsed = SelectionPolicyYaml.load(SelectionPolicyYaml.readText(file, OUTPUT_LIMIT), OUTPUT_LIMIT);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid Git snapshot");
        }
        if (!(parsed instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of("schemaVersion", "source", "failure", "availability", "baseRevision", "headRevision", "paths"))
                || !Integer.valueOf(1).equals(fields.get("schemaVersion")) || !(fields.get("paths") instanceof List<?> list)) {
            throw new IllegalArgumentException("Invalid Git snapshot");
        }
        try {
            var paths = new TreeSet<String>();
            for (Object value : list) if (!(value instanceof String path) || !paths.add(path)) throw new IllegalArgumentException("Invalid Git snapshot paths");
            return new Result(new SelectionContext.ChangeSet(SelectionContext.Availability.valueOf((String) fields.get("availability")), paths,
                    (String) fields.get("baseRevision"), (String) fields.get("headRevision")),
                    Source.valueOf((String) fields.get("source")), Failure.valueOf((String) fields.get("failure")));
        } catch (ClassCastException | NullPointerException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid Git snapshot values");
        }
    }

    private static Result unavailable(Source source, Failure failure, String head) {
        return new Result(new SelectionContext.ChangeSet(SelectionContext.Availability.UNKNOWN, Set.of(), "", head), source, failure);
    }

    private static String comparison(Commands git, String base, String head, boolean mergeBase) {
        if (!mergeBase) return base;
        // Criss-cross ancestry may have several best ancestors. Never silently choose the first one:
        // its incomplete impact would be indistinguishable from a complete successful acquisition.
        var result = git.run("merge-base", "--all", base, head);
        String text = decode(result.bytes()).trim();
        return result.code() == 0 && text.matches("[a-f0-9]{40}|[a-f0-9]{64}") ? text : "";
    }

    /** Git -z encodes status, old path and (for rename/copy) new path as separate NUL fields. */
    static Set<String> names(byte[] bytes) {
        String text = decode(bytes);
        if (!text.isEmpty() && !text.endsWith("\0")) throw new CommandFailure(Failure.INVALID_INPUT);
        String[] fields = text.split("\0", -1);
        var paths = new TreeSet<String>();
        for (int index = 0; index < fields.length - 1;) {
            String status = fields[index++];
            if (!status.matches("[ACDMRTUXB][0-9]*")) throw new CommandFailure(Failure.INVALID_INPUT);
            int count = status.startsWith("R") || status.startsWith("C") ? 2 : 1;
            if (index + count > fields.length - 1) throw new CommandFailure(Failure.INVALID_INPUT);
            for (int path = 0; path < count; path++) {
                try { SelectionGlob.path(fields[index], false); }
                catch (IllegalArgumentException invalid) { throw new CommandFailure(Failure.INVALID_INPUT); }
                paths.add(fields[index++]);
            }
            if (paths.size() > 20_000) throw new CommandFailure(Failure.OUTPUT_LIMIT);
        }
        return paths;
    }

    private record Base(String ref, Source source) { }

    private static Base infer(GitChangeOptions options, Map<String, String> env) throws IOException {
        // MR/PR bases describe the whole contribution. A push's before-SHA is used only when no MR
        // base is supplied; HEAD^ is deliberately never invented for new branches or shallow clones.
        String mr = env.getOrDefault("CI_MERGE_REQUEST_DIFF_BASE_SHA", "");
        if (!mr.isEmpty()) return new Base(mr, Source.GITLAB_MERGE_REQUEST);
        String target = env.getOrDefault("CI_MERGE_REQUEST_TARGET_BRANCH_NAME", "");
        if (!target.isEmpty()) return new Base("refs/remotes/" + options.remote() + "/" + target, Source.GITLAB_MERGE_REQUEST);
        String before = env.getOrDefault("CI_COMMIT_BEFORE_SHA", "");
        if (!before.isEmpty()) return new Base(before, Source.GITLAB_PUSH);
        String event = env.getOrDefault("GITHUB_EVENT_NAME", "");
        if (event.equals("pull_request") || event.equals("pull_request_target") || event.equals("push")) {
            String payload = env.getOrDefault("GITHUB_EVENT_PATH", "");
            if (!payload.isEmpty()) {
                Path file;
                try { file = Path.of(payload); }
                catch (java.nio.file.InvalidPathException invalid) { throw new IOException("Invalid CI event path"); }
                Object loaded;
                try { loaded = SelectionPolicyYaml.load(SelectionPolicyYaml.readText(file, 1_048_576), 1_048_576); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid CI event"); }
                if (loaded instanceof Map<?, ?> fields) {
                    if (event.equals("push") && fields.get("before") instanceof String value) return new Base(value, Source.GITHUB_PUSH);
                    if (fields.get("pull_request") instanceof Map<?, ?> pr && pr.get("base") instanceof Map<?, ?> base && base.get("sha") instanceof String value) {
                        return new Base(value, Source.GITHUB_PULL_REQUEST);
                    }
                }
            }
            String base = env.getOrDefault("GITHUB_BASE_REF", "");
            if (!base.isEmpty()) return new Base("refs/remotes/" + options.remote() + "/" + base, Source.GITHUB_PULL_REQUEST);
        }
        return new Base("", Source.UNAVAILABLE);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new CommandFailure(Failure.INVALID_INPUT); }
    }

    private record Output(int code, byte[] bytes) { }

    private static final class Commands {
        private final GitChangeOptions options;

        private Commands(GitChangeOptions options) { this.options = options; }

        private String resolve(String ref) {
            // Peel tags and reject trees/blobs while retaining the same full commit identity on every node.
            var output = run("rev-parse", "--verify", "--end-of-options", ref + "^{commit}");
            String value = decode(output.bytes()).trim();
            return output.code() == 0 && value.matches("[a-f0-9]{40}|[a-f0-9]{64}") ? value : "";
        }

        private byte[] checked(String... arguments) {
            var output = run(arguments);
            if (output.code() != 0) throw new CommandFailure(Failure.COMMAND_FAILED);
            return output.bytes();
        }

        private Output run(String... arguments) {
            var command = new ArrayList<>(List.of("git", "--no-pager", "-c", "protocol.ext.allow=never", "-C", options.directory().toString()));
            command.addAll(Arrays.asList(arguments));
            Process process = null;
            try {
                var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
                // Explicit -C must not be overridden by a parent build's repository-routing environment.
                for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_EXTERNAL_DIFF")) builder.environment().remove(key);
                builder.environment().put("GIT_TERMINAL_PROMPT", "0");
                builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
                process = builder.start();
                process.getOutputStream().close();
                Process running = process;
                // Drain concurrently: waiting for Git first can deadlock when its stdout fills the pipe.
                // Keep output and wait limits independent; no arbitrary stderr becomes a build diagnostic.
                var read = new FutureTask<>(() -> {
                    try (var stream = running.getInputStream()) {
                        byte[] data = stream.readNBytes(OUTPUT_LIMIT + 1);
                        if (data.length > OUTPUT_LIMIT) { running.destroyForcibly(); throw new CommandFailure(Failure.OUTPUT_LIMIT); }
                        return data;
                    }
                });
                var thread = new Thread(read, "shardpilot-git-output");
                thread.setDaemon(true);
                thread.start();
                if (!process.waitFor(options.timeoutSeconds(), TimeUnit.SECONDS)) throw new CommandFailure(Failure.TIMEOUT);
                return new Output(process.exitValue(), read.get(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CommandFailure(Failure.INTERRUPTED);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof CommandFailure known) throw known;
                throw new CommandFailure(Failure.COMMAND_FAILED);
            } catch (TimeoutException timeout) { throw new CommandFailure(Failure.TIMEOUT); }
            catch (IOException unavailable) { throw new CommandFailure(Failure.COMMAND_FAILED); }
            finally {
                if (process != null) {
                    if (process.isAlive()) process.destroyForcibly();
                    try { process.getInputStream().close(); } catch (IOException ignored) { /* No output may escape. */ }
                }
            }
        }
    }

    private static final class CommandFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Failure failure;

        private CommandFailure(Failure failure) { this.failure = failure; }
    }
}
