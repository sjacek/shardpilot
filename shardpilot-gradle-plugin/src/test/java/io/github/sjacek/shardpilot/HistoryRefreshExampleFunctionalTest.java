package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the documented consumer's actual build and test sources, rather than a second DSL copy.
 * TestKit substitutes only local plugin resolution so the same example runs on every matrix runtime.
 * Lost downloaded XML must invalidate a previous success without compiling or rerunning native tests.
 */
class HistoryRefreshExampleFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void refreshesBothNativeNodesAndRejectsMissingEvidenceWithoutAdoptingHistory() throws Exception {
        copyExample();
        byte[] maintained = Files.readAllBytes(directory.resolve("durations.tsv"));
        run("clean", "prepareTestShardManifests");
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=2");

        Path namespace = directory.resolve("build/shardpilot/test");
        Set<String> baseline = ClassManifest.read(namespace.resolve("plan/baseline.yaml"), false);
        assertEquals(Set.of("example.AlphaTest", "example.BetaTest"), baseline);
        Set<String> assigned = new HashSet<>();
        for (int index = 1; index <= 2; index++) {
            Set<String> planned = ClassManifest.read(namespace.resolve("plan/planned-" + index + ".yaml"), false);
            assertEquals(planned, ClassManifest.read(namespace.resolve("actual/actual-" + index + ".yaml"), false));
            for (String name : planned) assertTrue(assigned.add(name), "A family cannot occur on both nodes");
        }
        assertEquals(baseline, assigned);
        assertNativeCases();

        BuildResult first = run("verifyTestShards", "refreshTestShardDurations");
        assertNoProducers(first);
        assertEquals(TaskOutcome.SUCCESS, refreshOutcome(first));
        Path output = namespace.resolve("durations.tsv");
        Path report = namespace.resolve("durations.json");
        String expectedTsv = Files.readString(output);
        String expectedJson = Files.readString(report);
        Map<String, Double> candidate = DurationHistory.parse(expectedTsv);
        assertEquals(baseline, candidate.keySet());
        assertTrue(candidate.values().stream().allMatch(seconds -> Double.isFinite(seconds) && seconds >= .001));
        assertTrue(expectedJson.contains("\"status\":\"PASSED\""));
        assertTrue(expectedJson.contains("\"coverage\":1.0"));
        assertTrue(expectedJson.contains("\"dropped\":1"));
        assertTrue(expectedJson.contains("\"measuredSuites\":3"));
        String html = Files.readString(namespace.resolve("coverage.html"));
        assertTrue(html.contains("Verification: PASSED"));
        baseline.forEach(name -> assertTrue(html.contains(name)));

        BuildResult reused = run("verifyTestShards", "refreshTestShardDurations");
        assertNoProducers(reused);
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.UP_TO_DATE, refreshOutcome(reused));

        // Simulate one missing artifact download while retaining its valid class-selection manifest.
        // Selection coverage can still pass: refresh must independently detect incomplete measurements.
        Map<Path, byte[]> missing = new HashMap<>();
        try (var files = Files.walk(directory.resolve("build/results/shard-2"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                missing.put(file, Files.readAllBytes(file));
                Files.delete(file);
            }
        }
        assertFalse(missing.isEmpty());
        BuildResult failed = runner("verifyTestShards", "refreshTestShardDurations").buildAndFail();
        assertNoProducers(failed);
        assertEquals(TaskOutcome.SUCCESS, requiredOutcome(failed, ":verifyTestShards"));
        assertEquals(TaskOutcome.FAILED, refreshOutcome(failed));
        assertFalse(Files.exists(output), "A rejected refresh must remove the old successful TSV");
        String rejected = Files.readString(report);
        assertTrue(rejected.contains("\"status\":\"FAILED\""));
        assertTrue(rejected.contains("\"reason\":\"MINIMUM_COVERAGE\""));
        assertArrayEquals(maintained, Files.readAllBytes(directory.resolve("durations.tsv")));

        // The original cohort is deterministic, including the recovered TSV/JSON after a data failure.
        for (var entry : missing.entrySet()) Files.write(entry.getKey(), entry.getValue());
        BuildResult restored = run("verifyTestShards", "refreshTestShardDurations");
        assertNoProducers(restored);
        assertEquals(TaskOutcome.SUCCESS, refreshOutcome(restored));
        assertEquals(expectedTsv, Files.readString(output));
        assertEquals(expectedJson, Files.readString(report));
        assertArrayEquals(maintained, Files.readAllBytes(directory.resolve("durations.tsv")));
        assertNativeCases();
    }

    /** Copies only versioned fixture inputs, never local build outputs or cached report evidence. */
    private void copyExample() throws IOException {
        Path source = Path.of("examples/gradle/junit-jupiter/duration-history");
        for (String name : List.of("build.gradle", "gradle.properties", "durations.tsv")) {
            Files.copy(source.resolve(name), directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        try (var files = Files.walk(source.resolve("src"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = directory.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        // withPluginClasspath supplies the candidate; the published example retains includeBuild.
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'history-example-test'\n");
    }

    /** Native XML proves nested execution and disjoint nodes, not merely matching class manifests. */
    private void assertNativeCases() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var methods = new ArrayList<String>();
        try (var files = Files.walk(directory.resolve("build/results"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                var cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    var element = (org.w3c.dom.Element) cases.item(index);
                    methods.add(element.getAttribute("classname") + "#" + element.getAttribute("name"));
                }
            }
        }
        assertEquals(3, methods.size());
        assertEquals(Set.of("example.AlphaTest#outer()", "example.AlphaTest$Detail#nested()", "example.BetaTest#secondFamily()"),
                Set.copyOf(methods));
    }

    private static void assertNoProducers(BuildResult result) {
        for (String name : List.of("test", "compileJava", "compileTestJava", "testClasses",
                "prepareTestShardManifests", "writeTestActualShardManifest")) {
            assertNull(result.task(":" + name), "Artifact aggregation must not schedule " + name);
        }
    }

    private static TaskOutcome refreshOutcome(BuildResult result) {
        return requiredOutcome(result, ":refreshTestShardDurations");
    }

    private static TaskOutcome requiredOutcome(BuildResult result, String path) {
        return java.util.Objects.requireNonNull(result.task(path), "Expected task " + path).getOutcome();
    }

    private BuildResult run(String... arguments) { return runner(arguments).build(); }

    private GradleRunner runner(String... arguments) {
        var args = new ArrayList<>(List.of(arguments));
        args.addAll(List.of("--console=plain", "--stacktrace", "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(args);
    }
}
