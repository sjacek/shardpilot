package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real Gradle invocations prove opt-in, artifact-only behavior and strict Configuration Cache reuse. */
class ExecutionEvidenceFunctionalTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void verifiesRetainedCohortInGroovyAndKotlinWithoutSchedulingAnyProducer(boolean kotlin) throws Exception {
        fixture(kotlin);
        var first = runner().build();
        assertEquals(TaskOutcome.SUCCESS, outcome(first));
        noProducers(first);
        String before = json();
        var second = runner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertEquals(TaskOutcome.SUCCESS, outcome(second), "always revalidate, never UP-TO-DATE");
        assertEquals(before, json());
        noProducers(second);
        assertTrue(Files.readString(directory.resolve("build/shardpilot/test/execution-evidence.html")).contains("execution evidence: PASSED"));
    }

    @Test
    void changedEvidenceIsRecheckedAfterCacheReuseAndReplacesPreviousSuccessReports() throws Exception {
        fixture(false); runner().build();
        Path evidence = directory.resolve("downloaded/node-2/evidence.json");
        Files.writeString(evidence, Files.readString(evidence).replace("\"exitCode\": 0", "\"exitCode\": 137"));
        var failed = runner().buildAndFail(); noProducers(failed);
        assertTrue(failed.getOutput().contains("Reusing configuration cache."));
        assertTrue(json().contains("NATIVE_PROCESS_FAILED"));
        assertTrue(Files.readString(directory.resolve("build/shardpilot/test/execution-evidence.html")).contains("execution evidence: FAILED"));
    }

    @Test
    void buildCacheCannotRestoreAnEarlierSuccessAfterANativeFailure() throws Exception {
        fixture(false);
        // A consumer may enable caching broadly. C2's untracked state must still prevent a
        // restored PASSED report from replacing validation of the now-failed native process.
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                + "\ntasks.named('verifyTestExecutionEvidence') { outputs.cacheIf { true } }\n");
        noProducers(baseRunner("verifyTestExecutionEvidence", "--build-cache").build());
        Path evidence = directory.resolve("downloaded/node-2/evidence.json");
        Files.writeString(evidence, Files.readString(evidence).replace("\"exitCode\": 0", "\"exitCode\": 137"));
        Files.delete(directory.resolve("build/shardpilot/test/execution-evidence.json"));
        Files.delete(directory.resolve("build/shardpilot/test/execution-evidence.html"));
        var failed = baseRunner("verifyTestExecutionEvidence", "--build-cache").buildAndFail();
        assertEquals(TaskOutcome.FAILED, outcome(failed)); noProducers(failed);
        assertTrue(failed.getOutput().contains("Reusing configuration cache."));
        assertTrue(json().contains("NATIVE_PROCESS_FAILED"));
    }

    @Test
    void standaloneAggregationRequiresNeitherAJavaPluginNorAnyTestTask() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'io.github.sjacek.shardpilot' }
                tasks.register('verifyTestExecutionEvidence', io.github.sjacek.shardpilot.VerifyExecutionEvidence) {
                    expectedRequest = layout.projectDirectory.file('trusted/expected.json')
                    eligibilityFile = layout.projectDirectory.file('trusted/baseline.yaml')
                    selectionFile = layout.projectDirectory.file('trusted/selection.json')
                    nodesDirectory = layout.projectDirectory.dir('downloaded')
                    reportFile = layout.buildDirectory.file('shardpilot/test/execution-evidence.json')
                    htmlReportFile = layout.buildDirectory.file('shardpilot/test/execution-evidence.html')
                }
                """);
        var result = runner().build(); assertEquals(TaskOutcome.SUCCESS, outcome(result)); noProducers(result);
        assertTrue(json().contains("\"status\":\"PASSED\""));
    }

    @Test
    void missingInputCannotTriggerTestCompilationOrPlanningToManufactureEvidence() throws Exception {
        fixture(false); Files.delete(directory.resolve("downloaded/node-1/evidence.json"));
        noProducers(runner().buildAndFail());
        assertTrue(json().contains("INVALID_EVIDENCE"));
        assertFalse(Files.exists(directory.resolve("build/classes")));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsAProducerSymlinkWithoutGradleSnapshottingItsTargetFirst() throws Exception {
        fixture(false);
        Path reports = directory.resolve("downloaded/node-1/reports");
        Files.createSymbolicLink(reports.resolve("cycle"), reports);
        noProducers(runner().buildAndFail());
        assertTrue(json().contains("UNSAFE_ARTIFACT_PATH"));
    }

    @Test
    void applyingPluginAloneDoesNotRegisterOrRunC2() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c2-opt-in'\n");
        Files.writeString(directory.resolve("build.gradle"), "plugins { id 'java'; id 'io.github.sjacek.shardpilot' }\n");
        var result = baseRunner("tasks", "--all").build();
        assertFalse(result.getOutput().contains("verifyTestExecutionEvidence"));
    }

    /** Uses retained producer data only; the aggregation project's Java tasks are deliberate traps. */
    private void fixture(boolean kotlin) throws IOException {
        Path example = Path.of("docs/examples/execution-evidence-testdata");
        Files.createDirectories(directory.resolve("trusted"));
        for (String name : List.of("expected.json", "baseline.yaml", "selection.json")) Files.copy(example.resolve(name), directory.resolve("trusted/" + name));
        for (int index : List.of(1, 2)) {
            try (var files = Files.walk(example.resolve("node-" + index))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = directory.resolve("downloaded/node-" + index).resolve(example.resolve("node-" + index).relativize(file));
                    Files.createDirectories(target.getParent()); Files.copy(file, target);
                }
            }
        }
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c2-offline'\n");
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx256m\n");
        String build = kotlin ? """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                plugins { java; id("io.github.sjacek.shardpilot") }
                tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>().executionEvidence {
                    expectedRequest.set(layout.projectDirectory.file("trusted/expected.json"))
                    eligibilityFile.set(layout.projectDirectory.file("trusted/baseline.yaml"))
                    selectionFile.set(layout.projectDirectory.file("trusted/selection.json"))
                    nodesDirectory.set(layout.projectDirectory.dir("downloaded"))
                }
                tasks.withType<JavaCompile>().configureEach { doFirst { error("C2 must not compile") } }
                tasks.withType<Test>().configureEach { doFirst { error("C2 must not execute tests") } }
                """ : """
                plugins { id 'java'; id 'io.github.sjacek.shardpilot' }
                tasks.named('test', Test).get().shardPilot.executionEvidence {
                    expectedRequest = layout.projectDirectory.file('trusted/expected.json')
                    eligibilityFile = layout.projectDirectory.file('trusted/baseline.yaml')
                    selectionFile = layout.projectDirectory.file('trusted/selection.json')
                    nodesDirectory = layout.projectDirectory.dir('downloaded')
                }
                tasks.withType(JavaCompile).configureEach { doFirst { throw new GradleException('C2 must not compile') } }
                tasks.withType(Test).configureEach { doFirst { throw new GradleException('C2 must not execute tests') } }
                """;
        Files.writeString(directory.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), build);
    }

    private static void noProducers(BuildResult result) {
        assertNull(result.task(":test")); assertNull(result.task(":compileJava")); assertNull(result.task(":compileTestJava"));
        assertNull(result.task(":prepareTestShardManifests")); assertNull(result.task(":prepareTestSelection")); assertNull(result.task(":acquireTestGitChanges"));
    }
    private String json() throws IOException { return Files.readString(directory.resolve("build/shardpilot/test/execution-evidence.json")); }
    private static TaskOutcome outcome(BuildResult result) {
        var task = result.task(":verifyTestExecutionEvidence"); assertNotNull(task); return task.getOutcome();
    }
    private GradleRunner runner() { return baseRunner("verifyTestExecutionEvidence"); }
    private GradleRunner baseRunner(String... requested) {
        var arguments = new ArrayList<>(List.of(requested));
        arguments.addAll(List.of("--offline", "--stacktrace", "--console=plain"));
        return VerifiedTestKit.create(directory).withPluginClasspath().withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion())).withArguments(arguments);
    }
}
