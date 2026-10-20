package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.gradle.testkit.runner.TaskOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real nested Gradle builds, rather than mocked Gradle APIs or a replacement test runner. */
class ShardPilotFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void applyingThePluginAloneDoesNotShardTests() throws Exception {
        fixture(false);
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertEquals(8, cases("test").size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shardsCoverJupiterNestedAndSpockTestsWithoutDuplicates(boolean withHistory) throws Exception {
        fixture(true);
        if (withHistory) {
            write("durations.tsv", """
                    example.AlphaTest\t20
                    example.BetaTest\t19
                    example.GammaTest\t2
                    example.DeltaTest\t1
                    example.EchoSpec\t8
                    example.FoxtrotSpec\t7
                    """);
        }
        run("test");
        Set<String> baseline = cases("test");
        var union = new HashSet<String>();
        for (int shard = 1; shard <= 2; shard++) {
            run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=" + shard);
            var actual = cases("test");
            assertFalse(actual.isEmpty());
            assertTrue(actual.stream().noneMatch(union::contains), "a test ran on both shards");
            union.addAll(actual);
        }
        assertEquals(baseline, union);
        assertEquals(8, union.size());
        assertEquals(2, union.stream().filter(name -> name.contains("Spec#")).count());
    }

    @Test
    void legitimateEmptyShardDoesNotRetainAnotherShardsResults() throws Exception {
        fixture(true);
        write("durations.tsv", """
                example.AlphaTest\t0
                example.BetaTest\t0
                example.GammaTest\t0
                example.DeltaTest\t0
                example.EchoSpec\t0
                example.FoxtrotSpec\t0
                """);
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertEquals(8, cases("test").size());
        BuildResult empty = run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=2");
        assertTrue(Set.of(NO_SOURCE, SUCCESS).contains(testOutcome(empty)));
        assertTrue(cases("test").isEmpty(), "an empty shard must not publish stale JUnit reports");
    }

    @Test
    void configurationCacheIsReusableAndChangingHistoryInvalidatesCachedTestResults() throws Exception {
        fixture(true);
        write("durations.tsv", "example.AlphaTest\t20\nexample.BetaTest\t19\n");
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        BuildResult reused = run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(UP_TO_DATE, testOutcome(reused));
        write("durations.tsv", "example.AlphaTest\t19\nexample.BetaTest\t20\n");
        BuildResult changed = run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertEquals(SUCCESS, testOutcome(changed));
        var actual = cases("test");
        assertTrue(actual.stream().anyMatch(name -> name.startsWith("example.BetaTest#")));
        assertTrue(actual.stream().noneMatch(name -> name.startsWith("example.AlphaTest#")));
    }

    @Test
    void respectsExistingIncludesExcludesAndTasksRegisteredAfterPluginApplication() throws Exception {
        fixture(true);
        append("""
                tasks.register('selectedTests', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                    useJUnitPlatform()
                    include '**/AlphaTest*.class', '**/BetaTest.class'
                    exclude '**/BetaTest.class'
                    shardPilot { enabled = true }
                }
                """);
        int owner = ShardPlan.create(java.util.Map.of(), 2).shardFor("example.AlphaTest");
        run("selectedTests", "-Pshardpilot.count=2", "-Pshardpilot.index=" + owner);
        assertEquals(3, cases("selectedTests").size());
        assertTrue(cases("selectedTests").stream().allMatch(name -> name.contains("AlphaTest")));
    }

    @Test
    void commandLineTestSelectionStillRestrictsExecution() throws Exception {
        fixture(true);
        write("durations.tsv", "example.BetaTest\t20\nexample.AlphaTest\t19\n");
        run("test", "--tests", "example.BetaTest", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertEquals(Set.of("example.BetaTest#beta()"), cases("test"));
        BuildResult missing = runner("test", "--tests", "example.DoesNotExist").buildAndFail();
        assertTrue(missing.getOutput().contains("No tests found for given includes"));
    }

    @Test
    void malformedHistoryAndInvalidSettingsFailWithoutForgivingTestFailures() throws Exception {
        fixture(true);
        write("durations.tsv", "example.AlphaTest\tNaN\n");
        assertTrue(runner("test").buildAndFail().getOutput().contains("finite and nonnegative"));
        write("durations.tsv", "");
        assertTrue(runner("test", "-Pshardpilot.count=2", "-Pshardpilot.index=3")
                .buildAndFail().getOutput().contains("index must be between 1 and shard count"));
        write("src/test/java/example/BetaTest.java", """
                package example;
                import org.junit.jupiter.api.Test;
                class BetaTest {
                    @Test void beta() { throw new AssertionError("fixture failure remains fatal"); }
                }
                """);
        assertEquals(FAILED, testOutcome(runner("test").buildAndFail()));
    }

    @Test
    void worksWithTheKotlinDsl() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"kotlin-fixture\"\n");
        write("build.gradle.kts", """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                plugins {
                    java
                    id("io.github.sjacek.shardpilot")
                }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }
                tasks.test {
                    useJUnitPlatform()
                    extensions.configure<ShardPilotExtension>("shardPilot") {
                        enabled.set(true)
                    }
                }
                """);
        writeProperties();
        javaTest("Beta", "beta", "");
        run("test");
        assertEquals(Set.of("example.BetaTest#beta()"), cases("test"));
    }

    private void fixture(boolean enabled) throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'groovy'
                    id 'io.github.sjacek.shardpilot'
                }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation platform('org.junit:junit-bom:6.1.3')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testImplementation 'org.spockframework:spock-core:2.5-groovy-5.0'
                    testImplementation 'org.apache.groovy:groovy:5.1.3'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                test {
                    useJUnitPlatform()
                    maxHeapSize = '256m'
                }
                """);
        if (enabled) append("""
                test {
                    shardPilot {
                        enabled = true
                        durationsFile = layout.projectDirectory.file('durations.tsv')
                    }
                }
                """);
        writeProperties();
        javaTest("Alpha", "alpha", """
                @org.junit.jupiter.api.Nested
                class Nested {
                    @org.junit.jupiter.api.Test void nested() { }
                    @org.junit.jupiter.api.Test void alsoNested() { }
                }
                """);
        javaTest("Beta", "beta", "");
        javaTest("Gamma", "gamma", "");
        javaTest("Delta", "delta", "");
        for (String name : List.of("Echo", "Foxtrot")) {
            write("src/test/groovy/example/" + name + "Spec.groovy", """
                    package example
                    class %sSpec extends spock.lang.Specification {
                        def 'works'() { expect: true }
                    }
                    """.formatted(name));
        }
    }

    private void javaTest(String name, String method, String nested) throws IOException {
        write("src/test/java/example/" + name + "Test.java", """
                package example;
                class %sTest {
                    @org.junit.jupiter.api.Test void %s() { }
                    %s
                }
                """.formatted(name, method, nested));
    }

    private void writeProperties() throws IOException {
        write("gradle.properties", """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                org.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8
                org.gradle.workers.max=1
                """);
    }

    private BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    private static TaskOutcome testOutcome(BuildResult result) {
        return requireNonNull(result.task(":test"), "expected :test task was not scheduled").getOutcome();
    }

    private GradleRunner runner(String... arguments) {
        var options = new ArrayList<>(List.of(arguments));
        options.add("--stacktrace");
        options.add("--console=plain");
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath().withArguments(options);
    }

    private void write(String relative, String contents) throws IOException {
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private void append(String contents) throws IOException {
        Path path = directory.resolve("build.gradle");
        Files.writeString(path, Files.readString(path) + "\n" + contents);
    }

    private Set<String> cases(String task) throws Exception {
        var cases = new HashSet<String>();
        Path reports = directory.resolve("build/test-results/" + task);
        if (!Files.isDirectory(reports)) return cases;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                var suite = factory.newDocumentBuilder().parse(file.toFile());
                var tests = suite.getElementsByTagName("testcase");
                for (int index = 0; index < tests.getLength(); index++) {
                    var attributes = tests.item(index).getAttributes();
                    String identity = attributes.getNamedItem("classname").getNodeValue() + "#"
                            + attributes.getNamedItem("name").getNodeValue();
                    assertTrue(cases.add(identity), "duplicate execution in JUnit XML: " + identity);
                }
            }
        }
        return cases;
    }
}
