package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real native execution proves that a one-node selection is not bypassed by the shard shortcut. */
class TestSelectionFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void singleShardExpandsFamiliesKeepsFullArtifactsAndReusesConfigurationCache() throws Exception {
        fixture();
        write("build/shardpilot/test/coverage.json", "full-suite sentinel");
        var result = runner("prepareTestShardManifests", "test", "verifyTestShards").build();
        assertEquals(TaskOutcome.SUCCESS, requireNonNull(result.task(":test")).getOutcome());
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested", "example.BetaTest"),
                ClassManifest.read(output("actual/actual-1.yaml"), false));
        assertEquals(Set.of("example.AlphaTest#works()", "example.AlphaTest$Nested#nested()", "example.BetaTest#works()"), cases());
        assertTrue(Files.readString(output("selection/selection.json")).contains("\"eligibleClasses\":4"));
        assertTrue(Files.readString(output("coverage.html")).contains("Verification: PASSED"));
        assertEquals("full-suite sentinel", Files.readString(directory.resolve("build/shardpilot/test/coverage.json")));
        var reused = runner("prepareTestShardManifests", "test", "verifyTestShards").build();
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.UP_TO_DATE, requireNonNull(reused.task(":test")).getOutcome());
    }

    @Test
    void multipleShardsExecuteOnlySelectionAndVerifyIndependentEvidenceWithoutProducerTasks() throws Exception {
        fixture();
        runner("prepareTestShardManifests", "-Pshardpilot.count=2").build();
        var methods = new TreeSet<String>();
        for (int index = 1; index <= 2; index++) {
            runner("test", "-Pshardpilot.count=2", "-Pshardpilot.index=" + index).build();
            var current = cases();
            assertTrue(java.util.Collections.disjoint(methods, current));
            methods.addAll(current);
        }
        assertEquals(Set.of("example.AlphaTest#works()", "example.AlphaTest$Nested#nested()", "example.BetaTest#works()"), methods);
        assertEquals(Set.of("example.AlphaTest", "example.AlphaTest$Nested"), ClassManifest.read(output("plan/planned-1.yaml"), false));
        assertEquals(Set.of("example.BetaTest"), ClassManifest.read(output("plan/planned-2.yaml"), false));
        var verified = runner("verifyTestShards", "-Pshardpilot.count=2").build();
        assertNull(verified.task(":test"));
        assertNull(verified.task(":prepareTestSelection"));
        assertNull(verified.task(":prepareTestShardManifests"));
        assertTrue(Files.readString(output("coverage.json")).contains("\"classes\":3"));
        // Byte-identical class selections from a different manifest snapshot must still fail.
        Files.writeString(output("actual/selection-2.id"), "0".repeat(64));
        var failed = runner("verifyTestShards", "-Pshardpilot.count=2").buildAndFail();
        assertTrue(failed.getOutput().contains("Selection cohort differs for shard 2"));
        assertTrue(Files.readString(output("coverage.html")).contains("Verification: FAILED"));
        Files.delete(output("plan/selection.id"));
        Files.delete(output("actual/selection-1.id"));
        Files.delete(output("actual/selection-2.id"));
        assertTrue(runner("verifyTestShards", "-Pshardpilot.count=2").buildAndFail().getOutput()
                .contains("Explicit selection requires its planned cohort identity"));
    }

    @Test
    void manifestContentsInvalidateSelectionAndTestBuildCacheEvenWhenPathStaysTheSame() throws Exception {
        fixture();
        runner("test", "--build-cache").build();
        String previous = Files.readString(output("selection/selection.id"));
        ClassManifest.write(directory.resolve("selection.yaml"), Set.of("example.BetaTest"));
        var changed = runner("test", "--build-cache").build();
        assertEquals(TaskOutcome.SUCCESS, requireNonNull(changed.task(":test")).getOutcome());
        assertEquals(Set.of("example.BetaTest#works()"), cases());
        assertNotEquals(previous, Files.readString(output("selection/selection.id")));
        // A comment-only change also changes provenance, even when selected.yaml is unchanged.
        Files.writeString(directory.resolve("selection.yaml"), Files.readString(directory.resolve("selection.yaml")) + "# new input\n");
        var provenance = runner("test", "--build-cache").build();
        assertEquals(TaskOutcome.SUCCESS, requireNonNull(provenance.task(":test")).getOutcome());
    }

    @Test
    void cachedSelectionAndNativeResultsRestoreTogetherWithoutRunningTestsAgain() throws Exception {
        fixture();
        runner("test", "--build-cache").build();
        Path outputs = output("").toAbsolutePath().normalize();
        assertTrue(outputs.startsWith(directory.resolve("build").toAbsolutePath().normalize()));
        try (var files = Files.walk(outputs)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        var cached = runner("test", "--build-cache").build();
        assertEquals(TaskOutcome.FROM_CACHE, requireNonNull(cached.task(":prepareTestSelection")).getOutcome());
        assertEquals(TaskOutcome.FROM_CACHE, requireNonNull(cached.task(":test")).getOutcome());
        assertEquals(3, cases().size());
        assertTrue(Files.exists(output("actual/selection-1.id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion: 1\nclasses: []", "schemaVersion: 1\nclasses: [example.UnknownTest]",
            "schemaVersion: 1\nclasses: [example.BetaTest, example.BetaTest]", "schemaVersion: ["})
    void invalidManifestCannotExecuteOrPublishAnActualSelection(String yaml) throws IOException {
        fixture();
        write("selection.yaml", yaml);
        var failed = runner("test").buildAndFail();
        assertEquals(TaskOutcome.FAILED, requireNonNull(failed.task(":prepareTestSelection")).getOutcome());
        assertNull(failed.task(":test"));
        assertFalse(Files.exists(output("actual/actual-1.yaml")));
    }

    @Test
    void focusedCliFiltersAreRejectedInsteadOfPretendingTheWholeManifestExecuted() throws IOException {
        fixture();
        assertTrue(runner("test", "--tests", "example.BetaTest").buildAndFail().getOutput()
                .contains("Explicit selection cannot be combined"));
    }

    @Test
    void selectedNativeFailuresRemainFailuresAndRetainPreWorkerEvidence() throws IOException {
        fixture();
        write("src/test/java/example/BetaTest.java", "package example; class BetaTest { @org.junit.jupiter.api.Test void fails() { throw new AssertionError(); } }");
        var failed = runner("test").buildAndFail();
        assertEquals(TaskOutcome.FAILED, requireNonNull(failed.task(":test")).getOutcome());
        assertTrue(Files.exists(output("actual/actual-1.yaml")));
        assertTrue(Files.exists(output("actual/selection-1.id")));
    }

    @Test
    void selectionWithoutVerificationStillFiltersAndRejectsNarrowerNativeDiscovery() throws Exception {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("pilot.verification { eligibleClasses.from(eligible) }", ""));
        runner("test").build();
        assertEquals(3, cases().size());
        assertFalse(Files.exists(output("actual/actual-1.yaml")));
        // Empty candidates must fail before Test's NO-SOURCE shortcut can skip its actions.
        Files.writeString(build, Files.readString(build) + "\ntest { exclude '**/*' }\n");
        assertTrue(runner("test").buildAndFail().getOutput().contains("Native class filters differ"));
    }

    @Test
    void explicitPathOverridesAreIsolatedFromTheirFullSuiteCounterparts() throws IOException {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("pilot.verification { eligibleClasses.from(eligible) }", """
                pilot.verification {
                    eligibleClasses.from(eligible)
                    plannedDirectory = layout.buildDirectory.dir('custom/plan')
                    actualDirectory = layout.buildDirectory.dir('custom/actual')
                    reportFile = layout.buildDirectory.file('custom/report.json')
                    htmlReportFile = layout.buildDirectory.file('custom/report.html')
                }
                """));
        write("build/custom/plan/baseline.yaml", "full plan sentinel");
        runner("prepareTestShardManifests", "test", "verifyTestShards").build();
        assertTrue(Files.readString(directory.resolve("build/custom/selections/smoke/report.html")).contains("Verification: PASSED"));
        assertEquals("full plan sentinel", Files.readString(directory.resolve("build/custom/plan/baseline.yaml")));
    }

    @Test
    void kotlinDslUsesTheSameSelectionAndVerificationContract() throws Exception {
        fixture();
        Files.delete(directory.resolve("build.gradle"));
        write("build.gradle.kts", """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                plugins { java; id("io.github.sjacek.shardpilot") }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }
                tasks.test { useJUnitPlatform(); maxHeapSize = "256m" }
                val eligible = sourceSets["test"].output.classesDirs.asFileTree.matching {
                    include("**/*Test.class", "**/*Test\\$*.class")
                }
                val pilot = tasks.test.get().extensions.getByType<ShardPilotExtension>()
                pilot.enabled.set(true)
                pilot.selection {
                    manifestFile.set(layout.projectDirectory.file("selection.yaml"))
                    suite.set("smoke")
                    eligibleClasses.from(eligible)
                }
                pilot.verification { eligibleClasses.from(eligible) }
                """);
        runner("prepareTestShardManifests", "test", "verifyTestShards").build();
        assertEquals(3, cases().size());
        assertTrue(Files.readString(output("coverage.json")).contains("\"status\":\"passed\""));
    }

    @Test
    void selectedPlanningCannotBindAnotherExecutionCount() throws IOException {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("pilot.verification { eligibleClasses.from(eligible) }",
                "pilot.verification { eligibleClasses.from(eligible); shardCount = 2 }"));
        assertTrue(runner("prepareTestShardManifests").buildAndFail().getOutput()
                .contains("Explicit selection planning and execution must use the same shard count"));
        assertFalse(Files.exists(output("plan/baseline.yaml")));
    }

    @Test
    void disablingShardingCannotSilentlyIgnoreAConfiguredManifest() throws IOException {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build) + "\ntest { shardPilot.enabled = false }\n");
        assertTrue(runner("test").buildAndFail().getOutput().contains("Explicit selection requires shardPilot.enabled=true"));
    }

    /** An empty owned partition is legitimate; an unexpectedly empty native tree is not. */
    @Test
    void selectedEmptyShardClearsPreviousNativeResultsAndRetainsEmptyCandidateEvidence() throws Exception {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("pilot.verification { eligibleClasses.from(eligible) }",
                "pilot.verification { eligibleClasses.from(eligible); requireNonEmptyShards = false }"));
        runner("prepareTestShardManifests", "-Pshardpilot.count=3").build();
        var methods = new TreeSet<String>();
        for (int index = 1; index <= 3; index++) {
            var result = runner("test", "-Pshardpilot.count=3", "-Pshardpilot.index=" + index).build();
            var current = cases();
            assertTrue(java.util.Collections.disjoint(methods, current));
            methods.addAll(current);
            if (index == 3) {
                assertTrue(Set.of(TaskOutcome.NO_SOURCE, TaskOutcome.SUCCESS)
                        .contains(requireNonNull(result.task(":test")).getOutcome()));
                assertTrue(current.isEmpty(), "empty shard must not retain shard 2's XML");
            }
        }
        assertEquals(Set.of("example.AlphaTest#works()", "example.AlphaTest$Nested#nested()", "example.BetaTest#works()"), methods);
        assertTrue(ClassManifest.read(output("actual/actual-3.yaml"), true).isEmpty());
        assertEquals(Files.readString(output("plan/selection.id")), Files.readString(output("actual/selection-3.id")));
        runner("verifyTestShards", "-Pshardpilot.count=3").build();
        assertTrue(Files.readString(output("coverage.json")).contains("\"status\":\"passed\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"includeTestsMatching", "excludeTestsMatching"})
    void configuredNativeMethodFiltersCannotNarrowTheManifest(String method) throws IOException {
        fixture();
        Path build = directory.resolve("build.gradle");
        Files.writeString(build, Files.readString(build) + "\ntest { filter { " + method + " 'example.BetaTest' } }\n");
        var failed = runner("test").buildAndFail();
        assertEquals(TaskOutcome.FAILED, requireNonNull(failed.task(":test")).getOutcome());
        assertTrue(failed.getOutput().contains("Explicit selection cannot be combined"));
        assertFalse(Files.exists(output("test-results/TEST-example.BetaTest.xml")), "no worker should execute a focused filter");
    }

    @Test
    void invalidReplacementRemovesReusableSnapshotAndRecoveryUsesTheNewRequest() throws Exception {
        fixture();
        runner("test").build();
        String previous = Files.readString(output("actual/actual-1.yaml"));
        write("selection.yaml", "schemaVersion: 1\nclasses: [example.BetaTest, example.BetaTest]\n");
        var failed = runner("test").buildAndFail();
        assertNull(failed.task(":test"));
        assertFalse(Files.exists(output("selection/selected.yaml")));
        assertFalse(Files.exists(output("selection/selection.id")));
        assertTrue(Files.readString(output("selection/selection.json")).contains("\"status\":\"failed\""));
        assertTrue(Files.readString(output("selection/selection.html")).contains("Selection: FAILED"));
        // Old node evidence is retained, never presented as a new successful native execution.
        assertEquals(previous, Files.readString(output("actual/actual-1.yaml")));
        ClassManifest.write(directory.resolve("selection.yaml"), Set.of("example.BetaTest"));
        runner("test").build();
        assertEquals(Set.of("example.BetaTest#works()"), cases());
        assertTrue(Files.readString(output("selection/selection.json")).contains("\"status\":\"passed\""));
    }

    @Test
    void missingInputCannotDisableSelectionAndRunTheFullSuite() throws IOException {
        fixture();
        Files.delete(directory.resolve("selection.yaml"));
        var failed = runner("test").buildAndFail();
        assertNull(failed.task(":test"));
        assertFalse(Files.exists(output("actual/actual-1.yaml")));
        assertTrue(failed.getOutput().contains("selection.yaml"));
    }

    private void fixture() throws IOException {
        write("settings.gradle", "rootProject.name='selection-fixture'\nbuildCache { local { directory=file('cache') } }\n");
        write("gradle.properties", "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\n");
        write("build.gradle", """
                plugins { id 'java'; id 'io.github.sjacek.shardpilot' }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation platform('org.junit:junit-bom:6.1.3')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                test {
                    useJUnitPlatform()
                    maxHeapSize = '256m'
                    shardPilot { enabled = true; durationsFile = layout.projectDirectory.file('durations.tsv') }
                }
                def eligible = sourceSets.test.output.classesDirs.asFileTree.matching { include '**/*Test.class', '**/*Test$*.class' }
                def pilot = tasks.named('test', Test).get().shardPilot
                // Configure verification first to prove order-independent namespace conventions.
                pilot.verification { eligibleClasses.from(eligible) }
                pilot.selection {
                    manifestFile = layout.projectDirectory.file('selection.yaml')
                    suite = 'smoke'
                    eligibleClasses.from(eligible)
                }
                """);
        ClassManifest.write(directory.resolve("selection.yaml"), Set.of("example.AlphaTest$Nested", "example.BetaTest"));
        write("durations.tsv", "example.GammaTest\t1000\nexample.AlphaTest\t30\nexample.BetaTest\t20\n");
        write("src/test/java/example/AlphaTest.java", """
                package example;
                class AlphaTest {
                    @org.junit.jupiter.api.Test void works() { }
                    @org.junit.jupiter.api.Nested class Nested { @org.junit.jupiter.api.Test void nested() { } }
                }
                """);
        for (String name : List.of("Beta", "Gamma")) write("src/test/java/example/" + name + "Test.java",
                "package example; class " + name + "Test { @org.junit.jupiter.api.Test void works() { } }");
    }

    private Set<String> cases() throws Exception {
        var names = new TreeSet<String>();
        if (!Files.isDirectory(output("test-results"))) return names;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var files = Files.list(output("test-results"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                var cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    var attributes = cases.item(index).getAttributes();
                    assertTrue(names.add(attributes.getNamedItem("classname").getNodeValue() + "#"
                            + attributes.getNamedItem("name").getNodeValue()));
                }
            }
        }
        return names;
    }

    private Path output(String relative) { return directory.resolve("build/shardpilot/test/selections/smoke/" + relative); }

    private void write(String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private GradleRunner runner(String... arguments) {
        var options = new ArrayList<>(List.of(arguments));
        options.addAll(List.of("--stacktrace", "--console=plain", "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        return VerifiedTestKit.create(directory).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(options);
    }
}
