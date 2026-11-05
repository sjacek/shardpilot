package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Explicit opt-in subset of an independently established eligibility baseline. This boundary
 * never infers impact from source diffs. Family expansion cannot introduce ineligible bytecode.
 * @param suite safe consumer-owned identity, separate from the full suite
 * @param manifestSha256 SHA-256 of the exact supplied YAML bytes
 * @param eligible complete candidate baseline
 * @param requested validated explicit names
 * @param selected requested names expanded to their eligible class families
 */
public record TestSelection(String suite, String manifestSha256, Set<String> eligible,
                            Set<String> requested, Set<String> selected) {
    /**
     * Validates direct Java callers as strictly as the YAML reader. Selected names must be the
     * exact family closure within eligibility, rather than an arbitrary superset or partial family.
     * All three sets are copied: later consumer mutations cannot change a plan or cohort identity.
     */
    public TestSelection {
        validateSuite(suite);
        eligible = Set.copyOf(eligible);
        requested = Set.copyOf(requested);
        selected = Set.copyOf(selected);
        if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}") || requested.isEmpty()
                || eligible.stream().anyMatch(DurationHistory::invalidClassName) || !eligible.containsAll(requested)) {
            throw new IllegalArgumentException("Invalid explicit selection snapshot");
        }
        var families = new TreeSet<String>();
        requested.forEach(name -> families.add(DurationHistory.family(name)));
        var expanded = new TreeSet<String>();
        eligible.stream().filter(name -> families.contains(DurationHistory.family(name))).forEach(expanded::add);
        if (!expanded.equals(selected)) throw new IllegalArgumentException("Selection must contain exactly its eligible families");
    }

    /**
     * Reads the strict class-manifest v1 schema, rejecting empty, duplicate and foreign names.
     * @param manifest consumer selection file
     * @param eligible full eligible set established by the adapter
     * @param suite distinct suite identity
     * @return immutable validated selection
     * @throws IOException if the manifest cannot be read
     */
    public static TestSelection read(Path manifest, Set<String> eligible, String suite) throws IOException {
        validateSuite(suite);
        // Parse and hash the same read, so a changing input cannot combine one request with another hash.
        byte[] bytes = Files.readAllBytes(manifest);
        Set<String> requested = ClassManifest.parse(new String(bytes, StandardCharsets.UTF_8), false);
        var foreign = new TreeSet<>(requested);
        foreign.removeAll(eligible);
        if (!foreign.isEmpty()) throw new IllegalArgumentException("Selection contains ineligible classes: " + foreign);
        var families = new TreeSet<String>();
        requested.forEach(name -> families.add(DurationHistory.family(name)));
        var selected = new TreeSet<String>();
        eligible.stream().filter(name -> families.contains(DurationHistory.family(name))).forEach(selected::add);
        return new TestSelection(suite, sha256(bytes), eligible, requested, selected);
    }

    /**
     * Rejects unsafe path identities and the reserved full-suite namespace.
     * @param suite consumer identity
     */
    public static void validateSuite(String suite) {
        if (suite == null || !suite.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || suite.equalsIgnoreCase("full")) {
            throw new IllegalArgumentException("Selection suite must be a safe path component distinct from full");
        }
    }

    /**
     * Protects a maintained manifest from generated files, including existing hard-link aliases.
     * @param manifest maintained input
     * @param outputs generated file paths
     * @throws IOException if existing filesystem identities cannot be inspected
     */
    public static void protectInput(Path manifest, Path... outputs) throws IOException {
        Path source = manifest.toAbsolutePath().normalize();
        for (Path output : outputs) {
            if (source.equals(output.toAbsolutePath().normalize())
                    || Files.exists(source) && Files.exists(output) && Files.isSameFile(source, output)) {
                throw new IllegalArgumentException("Selection outputs cannot overwrite the input manifest");
            }
        }
    }

    /**
     * Restricts balancing metadata to selected families, retaining their nested timing records.
     * Unselected long-running classes must not consume bins or change the selected plan.
     * Validate the entire history before pruning; invalid foreign records cannot be hidden by selection.
     * @param history full history text
     * @param selected selected candidate names
     * @return validated subset of original duration records
     */
    public static Map<String, Double> history(String history, Set<String> selected) {
        var families = new TreeSet<String>();
        selected.forEach(name -> families.add(DurationHistory.family(name)));
        var measured = new TreeMap<>(DurationHistory.parse(history));
        measured.keySet().removeIf(name -> !families.contains(DurationHistory.family(name)));
        return measured;
    }

    /**
     * Binds independently produced plan/actual evidence to the same immutable inputs. Manifest bytes
     * are hashed exactly, while eligibility names are sorted; input set iteration order is irrelevant.
     * This identifies a candidate cohort, not the source commit or successful native test execution.
     * @param history full timing metadata
     * @param count total shards
     * @return deterministic cohort identity, independent of node index
     */
    public String identity(String history, int count) {
        ShardPlan.validateShard(count, 1);
        return sha256((suite + "\n" + manifestSha256 + "\n" + String.join("\n", new TreeSet<>(eligible))
                + "\n" + history + "\n" + count).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Records both baselines and deterministic JSON/standalone HTML selection diagnostics.
     * Coverage reports remain separate and still compare each node against the selected baseline.
     * @param directory suite-owned diagnostics directory
     * @param history immutable timing input
     * @param count total shards
     * @throws IOException if reports cannot be written
     */
    public void write(Path directory, String history, int count) throws IOException {
        var plan = ShardPlan.create(history(history, selected), count);
        double seconds = 0;
        for (int index = 1; index <= count; index++) seconds += plan.recordedSeconds(index);
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Selected duration estimate overflow");
        var expanded = new TreeSet<>(selected);
        expanded.removeAll(requested);
        String json = "{\"status\":\"passed\",\"suite\":\"" + suite + "\",\"manifestSha256\":\"" + manifestSha256
                + "\",\"eligibleClasses\":" + eligible.size() + ",\"requestedClasses\":" + requested.size()
                + ",\"selectedClasses\":" + selected.size() + ",\"familyExpansions\":["
                + String.join(",", expanded.stream().map(ShardCoverage::quote).toList())
                + "],\"shardCount\":" + count + ",\"measuredFamilies\":" + plan.recordedFamilies()
                + ",\"estimatedSeconds\":" + seconds + "}\n";
        ClassManifest.write(directory.resolve("eligible.yaml"), eligible);
        ClassManifest.write(directory.resolve("selected.yaml"), selected);
        Files.writeString(directory.resolve("selection.id"), identity(history, count), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("selection.json"), json, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("selection.html"), "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<title>ShardPilot selection: " + suite + "</title><h1>Selection: " + suite + "</h1><pre>"
                + json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</pre><p>Estimated seconds exclude unmeasured tests and JVM/application/container startup."
                + " Candidate selection does not prove execution success.</p></html>", StandardCharsets.UTF_8);
    }

    /**
     * Replaces earlier success diagnostics after invalid selection input. No valid snapshot remains
     * available to a subsequent candidate filter; native execution must retain the adapter failure.
     * @param directory selection-owned output directory
     * @param reason validation diagnostic, escaped for both report formats
     * @throws IOException if diagnostics cannot be written
     */
    public static void writeFailure(Path directory, String reason) throws IOException {
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("selected.yaml"));
        Files.deleteIfExists(directory.resolve("eligible.yaml"));
        Files.deleteIfExists(directory.resolve("selection.id"));
        String json = ShardCoverage.failed(reason);
        Files.writeString(directory.resolve("selection.json"), json, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("selection.html"), "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<title>ShardPilot selection failed</title><h1>Selection: FAILED</h1><pre>"
                + json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre></html>", StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }
}
