package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.EvidenceFiles.problem;
import static io.github.sjacek.shardpilot.ExecutionEvidence.Reason.*;

/**
 * Offline C2 validation shared by both adapters. Expected identity, eligibility and selection are
 * supplied independently of producer artifacts. Hashes bind bytes, not the honesty of a producer:
 * the caller's CI still authenticates job/artifact associations and requires native test jobs.
 * No tests, discovery, Git commands, compilation, downloads or archive extraction run here.
 */
@NullMarked
public final class ExecutionEvidence {
    private ExecutionEvidence() { }

    /** Bounded, payload-free diagnostics shared by JSON, HTML and adapter gate failures. */
    public enum Reason {
        /** Missing or invalid versioned metadata. */
        INVALID_EVIDENCE,
        /** Revision, project, run, suite, tool, runner or job differs. */
        COHORT_MISMATCH,
        /** Artifacts belong to another retry attempt. */
        ATTEMPT_MISMATCH,
        /** An expected node is absent. */
        MISSING_SHARD,
        /** An index or job identity is duplicated. */
        DUPLICATE_SHARD,
        /** An unrequested node is present. */
        UNEXPECTED_SHARD,
        /** The independently expected selection differs. */
        SELECTION_MISMATCH,
        /** Retained bytes differ from their declared digest. */
        ARTIFACT_HASH_MISMATCH,
        /** An artifact uses traversal, an alias or a symbolic link. */
        UNSAFE_ARTIFACT_PATH,
        /** Native XML is malformed or has an unsupported dialect. */
        INVALID_XML,
        /** A configured bounded-input limit was exceeded. */
        RESOURCE_LIMIT,
        /** The native process completed with a nonzero signed exit code. */
        NATIVE_PROCESS_FAILED,
        /** Native completion is absent or unsuccessful. */
        INCOMPLETE_PROCESS,
        /** Native rows, headers, ledger or producer counts disagree. */
        COUNTER_MISMATCH,
        /** Native invocation identity or report overlap is unresolved. */
        AMBIGUOUS_EXECUTION_IDENTITY,
        /** Plans do not partition the independently expected selection. */
        PLANNED_COVERAGE_FAILED,
        /** Native reports do not satisfy their node plans or contain failures. */
        REPORTED_COVERAGE_FAILED,
        /** The cohort executed fewer test invocations than required. */
        MINIMUM_EXECUTION_FAILED,
        /** Retained skips violate the independent policy. */
        SKIP_POLICY_FAILED,
        /** An empty node was not explicitly allowed or emitted reports. */
        UNEXPECTED_EMPTY_SHARD

    }

    /**
     * Effective input limits. Consumers may lower, but never raise, these distribution ceilings.
     * @param jsonBytes maximum bytes per metadata/ledger JSON document
     * @param manifestBytes maximum bytes per baseline/plan YAML document
     * @param maxNodes maximum expected nodes
     * @param maxReports maximum reports per node
     * @param maxReportBytes maximum bytes per XML report
     * @param maxTotalReportBytes maximum XML bytes per node
     * @param maxDepth maximum XML, manifest and artifact directory depth
     * @param maxTestCases maximum native rows per node
     */
    public record Limits(int jsonBytes, int manifestBytes, int maxNodes, int maxReports,
                         int maxReportBytes, int maxTotalReportBytes, int maxDepth, int maxTestCases) {
        /** Validates limits before any input or output access. */
        public Limits {
            int[] values = {jsonBytes, manifestBytes, maxNodes, maxReports, maxReportBytes, maxTotalReportBytes, maxDepth, maxTestCases};
            int[] ceilings = {1_048_576, 1_048_576, 1024, 20_000, 67_108_864, 268_435_456, 64, 2_000_000};
            for (int index = 0; index < values.length; index++) {
                if (values[index] < 1 || values[index] > ceilings[index]) throw new IllegalArgumentException("Invalid C2 resource limit");
            }
        }
        /** Provides the distribution ceilings.
         * @return distribution ceilings, also recorded in every aggregate report
         */
        public static Limits defaults() { return new Limits(1_048_576, 1_048_576, 1024, 20_000, 67_108_864, 268_435_456, 64, 2_000_000); }
    }

    /**
     * Artifact-only inputs; nodes are immediate {@code node-1}, {@code node-2}, etc. directories.
     * @param expectedRequest trusted CI-authored expected-cohort JSON
     * @param eligibility independently retained full native eligibility manifest
     * @param selection canonical full/explicit selection JSON, independently retained
     * @param nodesDirectory extracted producer nodes, kept read-only
     * @param limits effective bounded-input settings
     */
    public record Inputs(Path expectedRequest, Path eligibility, Path selection, Path nodesDirectory, Limits limits) { }

    /** Immutable, deterministic snapshot used by both renderers and the build-tool outcome.
     * @param passed whether all five independent gates passed
     * @param json payload-free deterministic JSON result
     * @param html escaped standalone HTML for the same evaluation
     */
    public record Evaluation(boolean passed, String json, String html) { }

    /**
     * Evaluates all available nodes. Missing/corrupt counts remain unknown instead of appearing as
     * a smaller successful cohort. Malformed input never escapes in diagnostics or exception causes.
     * @param inputs trusted request plus retained evidence
     * @return complete success/failure snapshot, including effective limits
     */
    public static Evaluation evaluate(Inputs inputs) {
        var state = new State(inputs.limits());
        try {
            byte[] requestBytes = EvidenceFiles.read(inputs.expectedRequest(), inputs.limits().jsonBytes());
            state.requestHash = EvidenceFiles.digest(requestBytes);
            var expected = expected(document(requestBytes), inputs.limits());
            state.cohort = expected.cohort;
            // Establish the trusted selected set before consuming producer claims. A matching
            // producer digest alone does not establish which suite this CI job intended to verify.
            Set<String> selected = selection(inputs, expected);
            var directories = directories(inputs.nodesDirectory(), inputs.limits());
            if (!directories.keySet().equals(expected.nodes.keySet())) {
                if (!expected.nodes.keySet().containsAll(directories.keySet())) state.fail("provenance", UNEXPECTED_SHARD);
            }
            var union = new TreeSet<String>();
            var invocationIds = new HashSet<String>();
            boolean plansKnown = true;
            for (var node : expected.nodes.entrySet()) {
                int index = node.getKey();
                var result = new Node(index);
                state.nodes.add(result);
                if (!directories.containsKey(index)) {
                    result.reasons.add(MISSING_SHARD);
                    state.fail("provenance", MISSING_SHARD);
                    state.unknown("nativeProcess", "plannedCoverage", "reportedCoverage", "skipPolicy");
                    plansKnown = false;
                    continue;
                }
                inspect(inputs, expected, node.getValue(), directories.get(index), result, state, invocationIds);
                if (result.planned == null) plansKnown = false;
                else {
                    var overlap = new TreeSet<>(union);
                    overlap.retainAll(result.planned);
                    if (!overlap.isEmpty()) {
                        result.reasons.add(PLANNED_COVERAGE_FAILED);
                        state.fail("plannedCoverage", PLANNED_COVERAGE_FAILED);
                    }
                    union.addAll(result.planned);
                }
            }
            if (plansKnown && !union.equals(selected)) state.fail("plannedCoverage", PLANNED_COVERAGE_FAILED);
            // Keep per-node observations for diagnosis, but never label foreign or partial rows
            // as a complete cohort total. Unknown totals must not satisfy minimum/skip thresholds.
            var totals = "PASSED".equals(state.gates.get("provenance")) ? completeTotals(state.nodes) : null;
            if (totals != null) {
                state.totals = totals;
                if (totals.executed() < expected.minimum) state.fail("reportedCoverage", MINIMUM_EXECUTION_FAILED);
                if (expected.maximumSkipped != null && totals.skipped() > expected.maximumSkipped) state.fail("skipPolicy", SKIP_POLICY_FAILED);
            } else state.unknown("reportedCoverage", "skipPolicy");
        } catch (EvidenceFiles.Problem failure) {
            state.fail("provenance", failure.reason);
            state.unknown("nativeProcess", "plannedCoverage", "reportedCoverage", "skipPolicy");
        } catch (IllegalArgumentException | ArithmeticException failure) {
            state.fail("provenance", INVALID_EVIDENCE);
            state.unknown("nativeProcess", "plannedCoverage", "reportedCoverage", "skipPolicy");
        }
        return state.report();
    }

    /** Returns no total when any node is incomplete; each nullable observation is captured once. */
    private static JUnitXmlExecutions.@Nullable Counts completeTotals(List<Node> nodes) {
        var total = JUnitXmlExecutions.Counts.zero();
        for (Node node : nodes) {
            var counts = node.counts;
            if (counts == null) return null;
            total = total.plus(counts);
        }
        return total;
    }

    /**
     * Writes fresh JSON/HTML on both success and evidence failure, then returns the gate outcome.
     * Output aliases are configuration errors and are rejected before touching inputs or reports.
     * @param inputs retained input files and limits
     * @param json separate aggregate JSON output
     * @param html separate standalone HTML output
     * @return the exact evaluation rendered to both outputs
     * @throws IOException if either output cannot be written
     */
    public static Evaluation writeReports(Inputs inputs, Path json, Path html) throws IOException {
        EvidenceFiles.protect(inputs, json, html);
        var result = evaluate(inputs);
        // Invalidate owned previous outputs before directory preparation can fail. Alias and type
        // validation above still precede every deletion. If an output itself cannot be removed,
        // the adapter must fail on I/O; an earlier artifact is never a substitute for that outcome.
        Files.deleteIfExists(json);
        Files.deleteIfExists(html);
        Files.createDirectories(json.toAbsolutePath().getParent());
        Files.createDirectories(html.toAbsolutePath().getParent());
        Files.writeString(json, result.json(), StandardCharsets.UTF_8);
        Files.writeString(html, result.html(), StandardCharsets.UTF_8);
        return result;
    }

    private record Expected(Map<String, @Nullable Object> cohort, Map<String, @Nullable Object> selection, int count,
                            Map<Integer, ExpectedNode> nodes, boolean rejectSkips, boolean allowEmpty,
                            long minimum, @Nullable Long maximumSkipped) { }
    private record ExpectedNode(int index, String jobId, String planHash, Instant started, Instant finished) { }

    private static Expected expected(@Nullable Object value, Limits limits) {
        var root = fields(value, "schemaVersion", "cohort", "selection", "shardCount", "expectedNodes", "skipPolicy", "allowEmptyShards", "minimumExecutedTests", "maximumSkippedTests");
        version(root);
        var cohort = cohort(root.get("cohort"));
        var selection = selectionIdentity(root.get("selection"), false);
        int count = (int) number(root.get("shardCount"), 1, 1024);
        if (count > limits.maxNodes()) throw problem(RESOURCE_LIMIT);
        var nodes = new TreeMap<Integer, ExpectedNode>();
        var jobs = new HashSet<String>();
        for (Object item : array(root.get("expectedNodes"), limits.maxNodes())) {
            var node = fields(item, "index", "jobId", "planSha256", "jobStartedAt", "jobFinishedAt");
            int index = (int) number(node.get("index"), 1, count);
            String job = identifier(node.get("jobId"));
            Instant started = timestamp(node.get("jobStartedAt")), finished = timestamp(node.get("jobFinishedAt"));
            if (finished.isBefore(started)) throw problem(INVALID_EVIDENCE);
            if (nodes.put(index, new ExpectedNode(index, job, hash(node.get("planSha256")), started, finished)) != null || !jobs.add(job)) throw problem(DUPLICATE_SHARD);
        }
        if (nodes.size() != count) throw problem(MISSING_SHARD);
        String skip = choice(root.get("skipPolicy"), "allow", "reject");
        Long maximum = root.get("maximumSkippedTests") == null ? null : number(root.get("maximumSkippedTests"), 0, Long.MAX_VALUE);
        return new Expected(cohort, selection, count, nodes, skip.equals("reject"), flag(root.get("allowEmptyShards")),
                number(root.get("minimumExecutedTests"), 0, Long.MAX_VALUE), maximum);
    }

    private static Set<String> selection(Inputs inputs, Expected expected) {
        byte[] baseline = EvidenceFiles.read(inputs.eligibility(), inputs.limits().manifestBytes());
        if (!EvidenceFiles.digest(baseline).equals(expected.selection.get("eligibilitySha256"))) throw problem(ARTIFACT_HASH_MISMATCH);
        Set<String> eligible = families(manifest(baseline, false, inputs.limits()));
        byte[] bytes = EvidenceFiles.read(inputs.selection(), inputs.limits().jsonBytes());
        if (!EvidenceFiles.digest(bytes).equals(expected.selection.get("sha256"))) throw problem(SELECTION_MISMATCH);
        var root = object(document(bytes));
        String mode = choice(root.get("mode"), "full", "explicit");
        fields(root, mode.equals("full") ? new String[] {"mode", "schemaVersion"} : new String[] {"families", "mode", "schemaVersion"});
        version(root);
        if (!mode.equals(expected.selection.get("mode"))) throw problem(SELECTION_MISMATCH);
        Set<String> selected = eligible;
        String canonical = "{\"mode\":\"full\",\"schemaVersion\":1}";
        if (mode.equals("explicit")) {
            var names = new TreeSet<String>();
            for (Object value : array(root.get("families"), inputs.limits().maxTestCases())) {
                String name = text(value);
                if (DurationHistory.invalidClassName(name) || name.contains("$") || !names.add(name)) throw problem(SELECTION_MISMATCH);
            }
            if (names.isEmpty() || !eligible.containsAll(names)) throw problem(SELECTION_MISMATCH);
            selected = names;
            canonical = "{\"families\":" + EvidenceJson.write(names) + ",\"mode\":\"explicit\",\"schemaVersion\":1}";
        }
        if (!EvidenceFiles.text(bytes).equals(canonical)) throw problem(SELECTION_MISMATCH);
        return selected;
    }

    private static Map<Integer, Path> directories(Path root, Limits limits) {
        EvidenceFiles.safe(root);
        var result = new TreeMap<Integer, Path>();
        if (!Files.exists(root)) return result;
        try (var entries = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path entry : entries) {
                if (++count > limits.maxNodes()) throw problem(RESOURCE_LIMIT);
                EvidenceFiles.safe(entry);
                String name = entry.getFileName().toString();
                if (!name.matches("node-[1-9][0-9]{0,3}") || !Files.isDirectory(entry)) throw problem(UNEXPECTED_SHARD);
                int index = Integer.parseInt(name.substring(5));
                if (result.put(index, entry) != null) throw problem(DUPLICATE_SHARD);
            }
        } catch (IOException failure) { throw problem(INVALID_EVIDENCE); }
        return result;
    }

    private static void inspect(Inputs inputs, Expected expected, ExpectedNode trusted, Path directory,
                                Node result, State state, Set<String> allInvocationIds) {
        try {
            Limits limits = inputs.limits();
            Set<String> foundReports = EvidenceFiles.reports(directory, limits);
            byte[] evidence = EvidenceFiles.read(directory.resolve("evidence.json"), limits.jsonBytes());
            result.hash = EvidenceFiles.digest(evidence);
            var root = object(document(evidence));
            var allowed = new HashSet<>(Set.of("schemaVersion", "cohort", "producer", "shard", "selection", "process", "reports", "observed"));
            if (root.containsKey("invocations")) allowed.add("invocations");
            fields(root, allowed.toArray(String[]::new));
            version(root);
            var actualCohort = cohort(root.get("cohort"));
            if (!Objects.equals(actualCohort.get("attempt"), expected.cohort.get("attempt"))) result.fail(state, "provenance", ATTEMPT_MISMATCH);
            if (!actualCohort.equals(expected.cohort)) result.fail(state, "provenance", COHORT_MISMATCH);
            var producer = fields(root.get("producer"), "jobId", "toolVersion", "adapterVersion");
            identifier(producer.get("toolVersion")); identifier(producer.get("adapterVersion"));
            if (!identifier(producer.get("jobId")).equals(trusted.jobId)) result.fail(state, "provenance", COHORT_MISMATCH);
            var shard = fields(root.get("shard"), "index", "count");
            if (number(shard.get("index"), 1, 1024) != trusted.index || number(shard.get("count"), 1, 1024) != expected.count) result.fail(state, "provenance", COHORT_MISMATCH);
            var selected = selectionIdentity(root.get("selection"), true);
            for (String key : expected.selection.keySet()) if (!Objects.equals(expected.selection.get(key), selected.get(key))) result.fail(state, "provenance", SELECTION_MISMATCH);
            if (!trusted.planHash.equals(selected.get("planSha256"))) result.fail(state, "provenance", ARTIFACT_HASH_MISMATCH);
            process(root.get("process"), trusted, result, state);
            // A subsequent XML/hash failure must not erase an already established process result.
            result.processKnown = true;
            byte[] plan = EvidenceFiles.read(directory.resolve("plan.yaml"), limits.manifestBytes());
            if (!EvidenceFiles.digest(plan).equals(trusted.planHash)) throw problem(ARTIFACT_HASH_MISMATCH);
            result.planned = families(manifest(plan, true, limits));
            var declared = new TreeSet<String>();
            var suites = new HashSet<String>();
            var identities = new HashSet<List<String>>();
            boolean ambiguous = false;
            long bytes = 0;
            int rows = 0;
            var byOwner = new TreeMap<String, JUnitXmlExecutions.Counts>();
            for (Object item : array(root.get("reports"), limits.maxReports())) {
                var descriptor = fields(item, "path", "sha256", "bytes");
                String name = text(descriptor.get("path"));
                Path file = EvidenceFiles.resolve(directory, name, true);
                if (!declared.add(name)) throw problem(INVALID_EVIDENCE);
                long length = number(descriptor.get("bytes"), 1, 67_108_864);
                if (length > limits.maxReportBytes()) throw problem(RESOURCE_LIMIT);
                if ((bytes = Math.addExact(bytes, length)) > limits.maxTotalReportBytes()) throw problem(RESOURCE_LIMIT);
                byte[] xml = EvidenceFiles.read(file, Math.min(length, limits.maxReportBytes()));
                if (xml.length != length || !EvidenceFiles.digest(xml).equals(hash(descriptor.get("sha256")))) throw problem(ARTIFACT_HASH_MISMATCH);
                for (var suite : JUnitXmlExecutions.read(xml, limits, limits.maxTestCases() - rows)) {
                    if (!suites.add(suite.name())) throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
                    for (var test : suite.cases()) {
                        rows++;
                        if (!identities.add(List.of(test.owner(), test.identity()))) ambiguous = true;
                        byOwner.merge(test.owner(), test.counts(), JUnitXmlExecutions.Counts::plus);
                    }
                }
            }
            if (!declared.equals(foundReports)) throw problem(INVALID_EVIDENCE);
            if (root.containsKey("invocations")) ledger(directory, root.get("invocations"), byOwner, limits, allInvocationIds);
            else if (ambiguous) throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
            var counts = JUnitXmlExecutions.Counts.zero();
            for (var value : byOwner.values()) counts = counts.plus(value);
            result.counts = counts;
            if (root.get("observed") != null && !counts.equals(observed(root.get("observed")))) result.fail(state, "reportedCoverage", COUNTER_MISMATCH);
            result.reported = families(byOwner.keySet());
            if (!result.reported.equals(result.planned)) result.fail(state, "reportedCoverage", REPORTED_COVERAGE_FAILED);
            if (result.planned.isEmpty() && (!expected.allowEmpty || !declared.isEmpty())) result.fail(state, "reportedCoverage", UNEXPECTED_EMPTY_SHARD);
            if (counts.failures() > 0 || counts.errors() > 0) result.fail(state, "reportedCoverage", REPORTED_COVERAGE_FAILED);
            if (expected.rejectSkips && counts.skipped() > 0) result.fail(state, "skipPolicy", SKIP_POLICY_FAILED);
        } catch (EvidenceFiles.Problem failure) {
            String gate = switch (failure.reason) {
                case INVALID_XML, COUNTER_MISMATCH, AMBIGUOUS_EXECUTION_IDENTITY -> "reportedCoverage";
                default -> "provenance";
            };
            result.fail(state, gate, failure.reason);
            if (!result.processKnown) state.unknown("nativeProcess");
            state.unknown("reportedCoverage", "skipPolicy");
            if (result.planned == null) state.unknown("plannedCoverage");
        } catch (IllegalArgumentException | ArithmeticException failure) {
            result.fail(state, "provenance", INVALID_EVIDENCE);
            if (!result.processKnown) state.unknown("nativeProcess");
            state.unknown("reportedCoverage", "skipPolicy");
            if (result.planned == null) state.unknown("plannedCoverage");
        }
    }

    private static void process(@Nullable Object value, ExpectedNode trusted, Node node, State state) {
        var process = fields(value, "state", "exitCode", "startedAt", "finishedAt");
        String status = choice(process.get("state"), "completed", "timed-out", "cancelled", "killed", "not-started", "unknown");
        Long exit = process.get("exitCode") == null ? null : number(process.get("exitCode"), Integer.MIN_VALUE, Integer.MAX_VALUE);
        Instant started = process.get("startedAt") == null ? null : timestamp(process.get("startedAt"));
        Instant finished = process.get("finishedAt") == null ? null : timestamp(process.get("finishedAt"));
        if (started != null && (started.isBefore(trusted.started) || started.isAfter(trusted.finished))
                || finished != null && (finished.isBefore(trusted.started) || finished.isAfter(trusted.finished))
                || started != null && finished != null && finished.isBefore(started)) throw problem(INVALID_EVIDENCE);
        if (status.equals("not-started") && (exit != null || started != null || finished != null)) throw problem(INVALID_EVIDENCE);
        if (status.equals("completed")) {
            if (exit == null || started == null || finished == null) throw problem(INVALID_EVIDENCE);
            if (exit != 0) node.fail(state, "nativeProcess", NATIVE_PROCESS_FAILED);
        } else node.fail(state, "nativeProcess", INCOMPLETE_PROCESS);
    }

    /**
     * A hashed native ledger resolves repeated display names; row positions never create identities.
     * IDs are unique across the entire cohort, not merely within one node. Per-owner terminal
     * counts must still match XML, so supplying a ledger cannot hide skipped or failed invocations.
     */
    private static void ledger(Path directory, @Nullable Object value, Map<String, JUnitXmlExecutions.Counts> xml,
                               Limits limits, Set<String> allIds) {
        var descriptor = fields(value, "path", "sha256", "bytes");
        Path file = EvidenceFiles.resolve(directory, text(descriptor.get("path")), false);
        long length = number(descriptor.get("bytes"), 1, limits.jsonBytes());
        byte[] bytes = EvidenceFiles.read(file, length);
        if (bytes.length != length || !EvidenceFiles.digest(bytes).equals(hash(descriptor.get("sha256")))) throw problem(ARTIFACT_HASH_MISMATCH);
        var ledger = fields(document(bytes), "schemaVersion", "runner", "invocations");
        version(ledger);
        String runner = choice(ledger.get("runner"), "junit-platform", "testng");
        var actual = new TreeMap<String, JUnitXmlExecutions.Counts>();
        for (Object item : array(ledger.get("invocations"), limits.maxTestCases())) {
            var row = fields(item, "id", "className", "outcome");
            String id = hash(row.get("id"));
            if (!allIds.add(runner + ":" + id)) throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
            String owner = text(row.get("className"));
            if (DurationHistory.invalidClassName(owner)) throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
            String outcome = choice(row.get("outcome"), "passed", "failed", "error", "skipped");
            actual.merge(owner, new JUnitXmlExecutions.Case(owner, "", outcome).counts(), JUnitXmlExecutions.Counts::plus);
        }
        if (!actual.equals(xml)) throw problem(COUNTER_MISMATCH);
    }

    private static Set<String> families(Set<String> classes) {
        var result = new TreeSet<String>();
        classes.forEach(name -> result.add(DurationHistory.family(name)));
        return result;
    }

    /** Bound YAML events before recursive composition, without altering legacy manifest readers. */
    private static Set<String> manifest(byte[] bytes, boolean allowEmpty, Limits limits) {
        String text = EvidenceFiles.text(bytes);
        var settings = org.snakeyaml.engine.v2.api.LoadSettings.builder().setAllowDuplicateKeys(false)
                .setMaxAliasesForCollections(0).setCodePointLimit(limits.manifestBytes()).build();
        try {
            int depth = 0, events = 0;
            for (var event : new org.snakeyaml.engine.v2.api.lowlevel.Parse(settings).parseString(text)) {
                if (++events > 100_000) throw problem(RESOURCE_LIMIT);
                if (event instanceof org.snakeyaml.engine.v2.events.CollectionStartEvent && ++depth > limits.maxDepth()) throw problem(RESOURCE_LIMIT);
                if (event instanceof org.snakeyaml.engine.v2.events.CollectionEndEvent) depth--;
            }
            return ClassManifest.parse(text, allowEmpty);
        } catch (org.snakeyaml.engine.v2.exceptions.YamlEngineException failure) { throw problem(INVALID_EVIDENCE); }
    }

    private static Map<String, @Nullable Object> cohort(@Nullable Object value) {
        var fields = fields(value, "revision", "runId", "attempt", "project", "suite", "tool", "runner");
        if (!text(fields.get("revision")).matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw problem(INVALID_EVIDENCE);
        for (String name : List.of("runId", "project", "suite")) identifier(fields.get(name));
        fields.put("attempt", number(fields.get("attempt"), 1, Integer.MAX_VALUE));
        String tool = choice(fields.get("tool"), "gradle", "maven");
        choice(fields.get("runner"), tool.equals("gradle") ? new String[] {"gradle-test"} : new String[] {"surefire", "failsafe"});
        return fields;
    }

    private static Map<String, @Nullable Object> selectionIdentity(@Nullable Object value, boolean plan) {
        var fields = fields(value, plan ? new String[] {"mode", "sha256", "eligibilitySha256", "planSha256"} : new String[] {"mode", "sha256", "eligibilitySha256"});
        choice(fields.get("mode"), "full", "explicit");
        hash(fields.get("sha256")); hash(fields.get("eligibilitySha256"));
        if (plan) hash(fields.get("planSha256"));
        return fields;
    }

    private static JUnitXmlExecutions.Counts observed(@Nullable Object value) {
        var fields = fields(value, "tests", "failures", "errors", "skipped");
        return new JUnitXmlExecutions.Counts(number(fields.get("tests"), 0, Long.MAX_VALUE), number(fields.get("failures"), 0, Long.MAX_VALUE),
                number(fields.get("errors"), 0, Long.MAX_VALUE), number(fields.get("skipped"), 0, Long.MAX_VALUE));
    }

    private static @Nullable Object document(byte[] bytes) { return EvidenceJson.parse(EvidenceFiles.text(bytes)); }
    @SuppressWarnings("unchecked") // The strict parser only creates String-keyed object maps.
    private static Map<String, @Nullable Object> object(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) throw problem(INVALID_EVIDENCE);
        return (Map<String, @Nullable Object>) map;
    }
    private static Map<String, @Nullable Object> fields(@Nullable Object value, String... keys) {
        var map = object(value);
        if (!map.keySet().equals(Set.of(keys))) throw problem(INVALID_EVIDENCE);
        return map;
    }
    private static List<?> array(@Nullable Object value, int maximum) {
        if (!(value instanceof List<?> list)) throw problem(INVALID_EVIDENCE);
        if (list.size() > maximum) throw problem(RESOURCE_LIMIT);
        return list;
    }
    private static String text(@Nullable Object value) {
        if (!(value instanceof String string)) throw problem(INVALID_EVIDENCE);
        return string;
    }
    private static String identifier(@Nullable Object value) {
        String string = text(value);
        if (string.length() > 200 || !string.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]*")) throw problem(INVALID_EVIDENCE);
        return string;
    }
    private static String hash(@Nullable Object value) {
        String string = text(value);
        if (!string.matches("[a-f0-9]{64}")) throw problem(INVALID_EVIDENCE);
        return string;
    }
    private static String choice(@Nullable Object value, String... options) {
        String string = text(value);
        if (!List.of(options).contains(string)) throw problem(INVALID_EVIDENCE);
        return string;
    }
    private static long number(@Nullable Object value, long minimum, long maximum) {
        if (!(value instanceof BigDecimal number)) throw problem(INVALID_EVIDENCE);
        long exact;
        try { exact = number.longValueExact(); } catch (ArithmeticException failure) { throw problem(INVALID_EVIDENCE); }
        if (exact < minimum || exact > maximum) throw problem(INVALID_EVIDENCE);
        return exact;
    }
    private static boolean flag(@Nullable Object value) {
        if (!(value instanceof Boolean flag)) throw problem(INVALID_EVIDENCE);
        return flag;
    }
    private static void version(Map<String, @Nullable Object> document) { number(document.get("schemaVersion"), 1, 1); }
    private static Instant timestamp(@Nullable Object value) {
        try {
            String text = text(value);
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|\\+00:00)")) throw problem(INVALID_EVIDENCE);
            var time = OffsetDateTime.parse(text);
            if (!time.getOffset().equals(ZoneOffset.UTC)) throw problem(INVALID_EVIDENCE);
            return time.toInstant();
        } catch (java.time.DateTimeException failure) { throw problem(INVALID_EVIDENCE); }
    }

    /** Available observations survive later failures; null records a boundary not reached safely. */
    private static final class Node {
        final int index;
        boolean processKnown;
        @Nullable String hash;
        @Nullable Set<String> planned, reported;
        JUnitXmlExecutions.@Nullable Counts counts;
        final Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        Node(int index) { this.index = index; }
        void fail(State state, String gate, Reason reason) { reasons.add(reason); state.fail(gate, reason); }
        Map<String, @Nullable Object> json() {
            var node = new LinkedHashMap<String, @Nullable Object>();
            node.put("index", index); node.put("evidenceSha256", hash); node.put("status", reasons.isEmpty() ? "PASSED" : "FAILED");
            node.put("plannedFamilies", planned == null ? null : planned.size()); node.put("reportedFamilies", reported == null ? null : reported.size());
            node.put("counts", counts(counts)); node.put("reasons", names(reasons));
            return node;
        }
    }

    /** Gate aggregation is monotone: FAILED dominates UNKNOWN, which dominates PASSED. */
    private static final class State {
        final Limits limits;
        @Nullable Map<String, @Nullable Object> cohort;
        @Nullable String requestHash;
        JUnitXmlExecutions.@Nullable Counts totals;
        final Map<String, String> gates = new LinkedHashMap<>();
        final Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        final List<Node> nodes = new ArrayList<>();
        State(Limits limits) {
            this.limits = limits;
            for (String gate : List.of("provenance", "nativeProcess", "plannedCoverage", "reportedCoverage", "skipPolicy")) gates.put(gate, "PASSED");
        }
        void fail(String gate, Reason reason) { gates.put(gate, "FAILED"); reasons.add(reason); }
        void unknown(String... unknown) { for (String gate : unknown) if (gates.get(gate).equals("PASSED")) gates.put(gate, "UNKNOWN"); }
        Evaluation report() {
            boolean passed = gates.values().stream().allMatch("PASSED"::equals);
            var json = new LinkedHashMap<String, @Nullable Object>();
            json.put("schemaVersion", 1); json.put("cohort", cohort); json.put("expectedRequestSha256", requestHash);
            json.put("status", passed ? "PASSED" : "FAILED"); json.put("gates", gates); json.put("totals", counts(totals));
            json.put("nodes", nodes.stream().map(Node::json).toList()); json.put("reasons", names(reasons));
            json.put("limits", Map.of("jsonBytes", limits.jsonBytes(), "manifestBytes", limits.manifestBytes(), "maxNodes", limits.maxNodes(),
                    "maxReports", limits.maxReports(), "maxReportBytes", limits.maxReportBytes(), "maxTotalReportBytes", limits.maxTotalReportBytes(),
                    "maxDepth", limits.maxDepth(), "maxTestCases", limits.maxTestCases()));
            // Map.of has unspecified iteration order across JVM processes: canonicalize this subsection.
            json.put("limits", new TreeMap<>(object(json.get("limits"))));
            String rendered = EvidenceJson.write(json) + "\n";
            var rows = new StringBuilder();
            gates.forEach((gate, status) -> rows.append("<tr><th>").append(gate).append("</th><td>").append(status).append("</td></tr>"));
            var nodeRows = new StringBuilder();
            for (Node node : nodes) {
                nodeRows.append("<tr><td>").append(node.index).append("</td><td>").append(node.reasons.isEmpty() ? "PASSED" : "FAILED")
                        .append("</td><td>").append(node.planned == null ? "unknown" : node.planned.size())
                        .append("</td><td>").append(node.reported == null ? "unknown" : node.reported.size())
                        .append("</td><td>").append(node.counts == null ? "unknown" : node.counts.executed())
                        .append("</td><td>").append(node.counts == null ? "unknown" : node.counts.skipped())
                        .append("</td><td>").append(escape(String.join(", ", names(node.reasons)))).append("</td></tr>");
            }
            String summary = totals == null ? "Complete cohort counters: unknown" : "Tests: " + totals.tests() + "; executed: " + totals.executed()
                    + "; failures: " + totals.failures() + "; errors: " + totals.errors() + "; skipped: " + totals.skipped();
            String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>ShardPilot execution evidence</title>"
                    + "<style>body{font:16px system-ui;max-width:1100px;margin:2rem auto;padding:1rem;color:#172c32}table{border-collapse:collapse}td,th{border:1px solid #aac4c7;padding:.6rem;text-align:left}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body>"
                    + "<h1>ShardPilot execution evidence: " + (passed ? "PASSED" : "FAILED") + "</h1><table><caption>Independent C2 gates</caption>" + rows + "</table>"
                    + "<h2>Cohort counters</h2><p>" + summary + "</p><table><caption>Node outcomes</caption><thead><tr><th>Node</th><th>Status</th><th>Planned families</th><th>Reported families</th><th>Executed</th><th>Skipped</th><th>Reasons</th></tr></thead><tbody>"
                    + nodeRows + "</tbody></table><h2>Retained evidence and limits</h2><pre>" + escape(rendered) + "</pre></body></html>\n";
            return new Evaluation(passed, rendered, html);
        }
    }

    private static @Nullable Map<String, @Nullable Object> counts(JUnitXmlExecutions.@Nullable Counts counts) {
        if (counts == null) return null;
        var map = new LinkedHashMap<String, @Nullable Object>();
        map.put("tests", counts.tests()); map.put("executed", counts.executed()); map.put("failures", counts.failures()); map.put("errors", counts.errors()); map.put("skipped", counts.skipped());
        return map;
    }
    private static List<String> names(Set<Reason> reasons) { return reasons.stream().map(Enum::name).sorted().toList(); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
