package io.github.sjacek.shardpilot;

import io.github.sjacek.shardpilot.testing.VerifyTestExecutions;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.testkit.runner.UnexpectedBuildFailure;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native engine events prove execution equivalence independently of ShardPilot's class manifests. */
class ExecutionEquivalenceFunctionalTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"junit", "spock", "testng"})
    void nativeBaselineEqualsTwoAndFourShardsIncludingInvocationsAndOutcomes(String framework) throws Exception {
        fixture(framework);
        Path evidence = Path.of("shardpilot-gradle-plugin/build/reports/execution-equivalence", framework);
        Files.createDirectories(evidence);
        Files.deleteIfExists(evidence.resolve("two-shards.json"));
        Files.deleteIfExists(evidence.resolve("four-shards.json"));
        Path baseline = execute(framework, "baseline", 1, 1, false, evidence);
        var nativeRun = VerifyTestExecutions.read(baseline);
        assertEquals(framework.equals("testng")
                        ? Map.of("PASSED", 8, "FAILED", 4, "ERROR", 1, "SKIPPED", 2)
                        : Map.of("PASSED", 8, "FAILED", 4, "ERROR", 1, "SKIPPED", 1, "ABORTED", 1),
                nativeRun.outcomes(), "the reference must exercise passing, failing, errored and skipped native tests");
        if (framework.equals("junit")) {
            assertTrue(nativeRun.occurrences().keySet().stream().anyMatch(e -> e.identity().contains("nested-class:")));
            assertEquals(3, nativeRun.occurrences().keySet().stream()
                    .filter(e -> e.identity().contains("test-template:rows")).count());
        }
        for (int count : List.of(2, 4)) {
            var shards = new ArrayList<Path>();
            for (int index = 1; index <= count; index++) {
                shards.add(execute(framework, count + "-" + index, count, index, true, evidence));
            }
            var comparison = VerifyTestExecutions.verify(evidence.resolve(count == 2 ? "two-shards.json" : "four-shards.json"),
                    baseline, shards);
            assertTrue(comparison.passed(), comparison.json());
            assertEquals(nativeRun.executions(), comparison.shardExecutions());
        }
    }

    private Path execute(String framework, String cohort, int count, int index, boolean sharded, Path evidence) throws Exception {
        Path output = directory.resolve("build/executions/" + cohort + "/events.tsv");
        Files.deleteIfExists(output);
        BuildResult result;
        try {
            result = runner("test", "-Pcohort=" + cohort, "-Psharded=" + sharded,
                    "-Pshardpilot.count=" + count, "-Pshardpilot.index=" + index).build();
        } catch (UnexpectedBuildFailure expectedFixtureFailure) {
            result = expectedFixtureFailure.getBuildResult();
        }
        Files.writeString(evidence.resolve(cohort + "-build.log"), result.getOutput());
        var testTask = result.task(":test");
        assertNotNull(testTask, "a configuration error is not an expected fixture test failure");
        assertTrue(Files.isRegularFile(output), "native listener evidence is missing for " + framework + "/" + cohort);
        var snapshot = VerifyTestExecutions.read(output);
        assertTrue(snapshot.executions() > 0, "every planned node owns a real test family in this fixture");
        boolean failed = snapshot.outcomes().containsKey("FAILED") || snapshot.outcomes().containsKey("ERROR");
        assertEquals(failed ? TaskOutcome.FAILED : TaskOutcome.SUCCESS, testTask.getOutcome(),
                "native failures must remain failures; do not configure ignoreFailures");
        Path retained = evidence.resolve(cohort + ".tsv");
        Files.copy(output, retained, StandardCopyOption.REPLACE_EXISTING);
        Path nativeXml = evidence.resolve(cohort + "-xml");
        Files.createDirectories(nativeXml);
        try (var reports = Files.list(output.getParent().resolve("xml"))) {
            for (Path report : reports.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                Files.copy(report, nativeXml.resolve(report.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return retained;
    }

    private void fixture(String framework) throws IOException {
        for (String sources : framework.equals("spock") ? List.of("junit", "spock") : List.of(framework)) {
            Path root = Path.of("testing/execution-equivalence", sources);
            try (var paths = Files.walk(root)) {
                for (Path input : paths.filter(Files::isRegularFile).toList()) {
                    Path destination = directory.resolve("fixtures/" + sources).resolve(root.relativize(input));
                    Files.createDirectories(destination.getParent());
                    Files.copy(input, destination);
                }
            }
        }
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='execution-equivalence'\n");
        Files.writeString(directory.resolve("gradle.properties"), """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                org.gradle.workers.max=1
                org.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8
                """);
        String dependencies = switch (framework) {
            case "junit" -> """
                    testImplementation platform('org.junit:junit-bom:6.1.3')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testImplementation 'org.junit.platform:junit-platform-launcher'
                    """;
            case "spock" -> """
                    testImplementation 'org.spockframework:spock-core:2.5-groovy-5.0'
                    testImplementation 'org.apache.groovy:groovy:5.1.3'
                    testImplementation 'org.junit.platform:junit-platform-launcher:6.1.3'
                    """;
            case "testng" -> "testImplementation 'org.testng:testng:7.12.0'";
            default -> throw new IllegalArgumentException(framework);
        };
        String sources = framework.equals("spock") ? """
                sourceSets.test.java.setSrcDirs(['fixtures/junit/src'])
                sourceSets.test.java.include('**/JunitExecutionListener.java')
                sourceSets.test.groovy.setSrcDirs(['fixtures/spock/src'])
                sourceSets.test.resources.setSrcDirs(['fixtures/junit/resources'])
                """ : "sourceSets.test.java.setSrcDirs(['fixtures/" + framework + "/src'])\n"
                + "sourceSets.test.resources.setSrcDirs(['fixtures/" + framework + "/resources'])\n";
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'groovy'; id 'io.github.sjacek.shardpilot' apply false }
                repositories { mavenCentral() }
                dependencies { %s }
                %s
                def sharded = providers.gradleProperty('sharded').get().toBoolean()
                if (sharded) apply plugin: 'io.github.sjacek.shardpilot'
                def cohort = providers.gradleProperty('cohort').get()
                def evidence = layout.buildDirectory.file('executions/' + cohort + '/events.tsv')
                test {
                    %s
                    maxHeapSize = '256m'
                    maxParallelForks = 1
                    failFast = false
                    inputs.property('executionCohort', cohort)
                    outputs.file(evidence)
                    systemProperty 'execution.evidence', evidence.get().asFile.absolutePath
                    reports.junitXml.outputLocation = layout.buildDirectory.dir('executions/' + cohort + '/xml')
                    if (sharded) shardPilot {
                        enabled = true
                        durationsFile = layout.projectDirectory.file('durations.tsv')
                    }
                }
                """.formatted(dependencies, sources, framework.equals("testng")
                ? "useTestNG { listeners << 'example.equivalence.TestNgExecutionListener' }" : "useJUnitPlatform()"));
        var history = new StringBuilder();
        for (String name : List.of("Alpha", "Beta", "Gamma", "Delta")) {
            history.append("example.equivalence.").append(name).append(framework.equals("spock") ? "Spec" : "Test").append("\t10\n");
        }
        Files.writeString(directory.resolve("durations.tsv"), history);
    }

    private GradleRunner runner(String... arguments) {
        var options = new ArrayList<>(List.of(arguments));
        options.addAll(List.of("--console=plain", "--stacktrace", "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        return VerifiedTestKit.create(directory).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(options);
    }
}
