package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.util.GradleVersion;
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
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.gradle.testkit.runner.TaskOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real nested Gradle builds, rather than mocked Gradle APIs or a replacement test runner. */
class ShardPilotFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void consumerBuildAndTestWorkerUseTheRequestedGradleAndJavaRuntime() throws Exception {
        fixture(false);
        String javaVersion = System.getProperty("shardpilot.test.javaVersion", Integer.toString(Runtime.version().feature()));
        assertEquals(javaVersion, Integer.toString(Runtime.version().feature()), "outer test worker must use the matrix JDK");
        append("""
                def runtime = "TESTKIT_RUNTIME Gradle ${gradle.gradleVersion} / JDK ${JavaVersion.current().majorVersion}"
                tasks.register('verifyRuntime') {
                    doLast { logger.lifecycle(runtime) }
                }
                """);
        write("src/test/java/example/RuntimeTest.java", """
                package example;
                class RuntimeTest {
                    @org.junit.jupiter.api.Test void usesTheMatrixJdk() {
                        org.junit.jupiter.api.Assertions.assertEquals("%s", System.getProperty("java.specification.version"));
                    }
                }
                """.formatted(javaVersion));
        BuildResult result = run("verifyRuntime", "test", "--tests", "example.RuntimeTest");
        assertTrue(result.getOutput().contains("TESTKIT_RUNTIME Gradle " + consumerGradleVersion() + " / JDK " + javaVersion));
        assertEquals(Set.of("example.RuntimeTest#usesTheMatrixJdk()"), cases("test"));
    }

    @Test
    void applyingThePluginAloneDoesNotShardTests() throws Exception {
        fixture(false);
        append("""
                test {
                    forkEvery = 7
                    maxParallelForks = 1
                    doFirst { Test task ->
                        assert task.maxHeapSize == '256m'
                        assert task.forkEvery == 7
                        assert task.maxParallelForks == 1
                    }
                }
                """);
        run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=1");
        assertEquals(8, cases("test").size());
    }

    @Test
    void processOptionsAreIndependentOfShardingAndLeaveUnspecifiedTaskSettingsUntouched() throws Exception {
        fixture(false);
        append("""
                test {
                    forkEvery = 7
                    shardPilot {
                        testProcesses { maxParallelForks = providers.provider { 1 } }
                    }
                    doFirst { Test task ->
                        assert task.maxHeapSize == '256m'
                        assert task.forkEvery == 7
                        assert task.maxParallelForks == 1
                    }
                }
                """);
        run("test", "--tests", "example.BetaTest", "-Pshardpilot.count=2", "-Pshardpilot.index=99");
        assertEquals(Set.of("example.BetaTest#beta()"), cases("test"));
    }

    @Test
    void processPolicyLimitsRealWorkerHeapsAndRecyclesProcessesWithReusableConfigurationCache() throws Exception {
        processFixture(false);
        run("test", "--tests", "example.*ProcessTest", "-PprocessForkEvery=0");
        assertEquals(1, probeWorkers(128).size(), "zero must reuse the process across both classes");
        BuildResult recycled = run("test", "--tests", "example.*ProcessTest", "-PprocessForkEvery=1");
        assertEquals(SUCCESS, testOutcome(recycled), "changing only forkEvery must invalidate test results");
        assertEquals(2, probeWorkers(128).size(), "each class must execute in a fresh Gradle worker");
        assertEquals(2, cases("test").size());

        BuildResult reused = run("test", "--tests", "example.*ProcessTest", "-PprocessForkEvery=1");
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(UP_TO_DATE, testOutcome(reused));
        // Remove declared probe outputs to execute workers from the restored configuration as well.
        for (String name : List.of("First", "Second")) {
            Files.delete(directory.resolve("build/process-probes/" + name));
        }
        BuildResult restored = run("test", "--tests", "example.*ProcessTest", "-PprocessForkEvery=1");
        assertTrue(restored.getOutput().contains("Reusing configuration cache."));
        assertEquals(SUCCESS, testOutcome(restored));
        assertEquals(2, probeWorkers(128).size());

        run("test", "--tests", "example.*ProcessTest", "-PprocessForkEvery=1", "-PprocessHeap=160m");
        assertEquals(2, probeWorkers(160).size(), "provider overrides must reach actual worker JVMs");
    }

    @Test
    void processPolicyAllowsTwoRealWorkersWithoutChangingFrameworkOrShardSelection() throws Exception {
        processFixture(true);
        run("test", "--tests", "example.*ProcessTest", "--max-workers=2");
        assertEquals(2, probeWorkers(128).size());
        assertEquals(2, cases("test").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"maxHeapSize = '0m'", "maxParallelForks = 0", "forkEvery = -1L"})
    void invalidProcessPoliciesFailBeforeExecutingTests(String setting) throws Exception {
        fixture(false);
        append("test { shardPilot { testProcesses { " + setting + " } } }\n");
        BuildResult failure = runner("test").buildAndFail();
        assertTrue(failure.getOutput().contains("ShardPilot testProcesses."));
        assertFalse(Files.exists(directory.resolve("build/test-results/test")));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testNgDataProviderRowsStayWithTheirClassWithoutCoverageGaps(boolean withHistory) throws Exception {
        testNgFixture();
        if (withHistory) {
            write("durations.tsv", "example.AlphaTest\t3\nexample.EchoTest\t1\n");
        }
        run("test");
        Set<String> baseline = cases("test");
        Set<String> alpha = baseline.stream().filter(name -> name.startsWith("example.AlphaTest#"))
                .collect(Collectors.toSet());
        assertEquals(4, baseline.size());
        assertEquals(3, alpha.size());
        assertEquals(2, alpha.stream().filter(name -> name.contains("#row")).count());

        var union = new HashSet<String>();
        for (int shard = 1; shard <= 2; shard++) {
            run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=" + shard);
            Set<String> actual = cases("test");
            assertFalse(actual.isEmpty());
            assertTrue(actual.stream().noneMatch(union::contains), "a TestNG invocation ran on both shards");
            if (actual.stream().anyMatch(alpha::contains)) {
                assertTrue(actual.containsAll(alpha), "data-provider rows and ordinary methods must stay together");
            }
            union.addAll(actual);
        }
        assertEquals(baseline, union);

        BuildResult reused = run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=2");
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(UP_TO_DATE, testOutcome(reused));
        if (withHistory) {
            // Two positively weighted classes cannot occupy all three shards; no old reports may remain.
            BuildResult empty = run("test", "-Pshardpilot.count=3", "-Pshardpilot.index=3");
            assertTrue(Set.of(NO_SOURCE, SUCCESS).contains(testOutcome(empty)));
            assertTrue(cases("test").isEmpty());
        }
    }

    @Test
    void testNgBeforeClassFailureRemainsFatalInsteadOfBecomingOnlySkippedTests() throws Exception {
        testNgFixture();
        write("src/test/java/example/AlphaTest.java", """
                package example;
                public class AlphaTest {
                    @org.testng.annotations.BeforeClass
                    public void initialize() { throw new IllegalStateException("intentional TestNG setup failure"); }
                    @org.testng.annotations.Test
                    public void neverRuns() { throw new AssertionError("setup must prevent execution"); }
                }
                """);
        assertEquals(FAILED, testOutcome(runner("test").buildAndFail()));
        assertTrue(Files.readString(directory.resolve("build/test-results/test/TEST-example.AlphaTest.xml"))
                .contains("intentional TestNG setup failure"));
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
        append("test { shardPilot { testProcesses { maxHeapSize = '128m'; forkEvery = 1L } } }\n");
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
                        testProcesses {
                            maxHeapSize.set("128m")
                            maxParallelForks.set(1)
                            forkEvery.set(1L)
                        }
                    }
                }
                """);
        writeProperties();
        javaTest("Beta", "beta", "");
        run("test");
        assertEquals(Set.of("example.BetaTest#beta()"), cases("test"));
    }

    private void processFixture(boolean parallel) throws IOException {
        fixture(false);
        append("""
                test {
                    // File selection happens before worker assignment; CLI method filters alone do not.
                    include '**/*ProcessTest.class'
                    shardPilot {
                        enabled = %s
                        testProcesses {
                            maxHeapSize = providers.gradleProperty('processHeap').orElse('128m')
                            maxParallelForks = %d
                            forkEvery = providers.gradleProperty('processForkEvery').map { Long.parseLong(it) }.orElse(0L)
                        }
                    }
                    systemProperty 'probeDirectory', layout.buildDirectory.dir('process-probes').get().asFile.absolutePath
                    systemProperty 'parallelProbe', '%s'
                    outputs.dir(layout.buildDirectory.dir('process-probes'))
                    doFirst { Test task ->
                        assert task.maxParallelForks == %d
                    }
                }
                """.formatted(parallel, parallel ? 2 : 1, parallel, parallel ? 2 : 1));
        for (String name : List.of("First", "Second")) {
            write("src/test/java/example/" + name + "ProcessTest.java", """
                    package example;
                    import java.nio.file.*;
                    import java.time.*;
                    class %sProcessTest {
                        @org.junit.jupiter.api.Test void recordsProcess() throws Exception {
                            Path root = Path.of(System.getProperty("probeDirectory"));
                            Files.createDirectories(root);
                            Files.writeString(root.resolve("%s"), ProcessHandle.current().pid() + " "
                                    + Runtime.getRuntime().maxMemory());
                            if (Boolean.getBoolean("parallelProbe")) {
                                Instant deadline = Instant.now().plusSeconds(30);
                                while (!Files.exists(root.resolve("First")) || !Files.exists(root.resolve("Second"))) {
                                    if (Instant.now().isAfter(deadline)) throw new AssertionError("Two concurrent workers required");
                                    Thread.sleep(20);
                                }
                            }
                        }
                    }
                    """.formatted(name, name));
        }
    }

    private Set<String> probeWorkers(int heapMiB) throws IOException {
        var workers = new HashSet<String>();
        for (String name : List.of("First", "Second")) {
            String[] probe = Files.readString(directory.resolve("build/process-probes/" + name)).split(" ");
            workers.add(probe[0]);
            long heap = Long.parseLong(probe[1]);
            long limit = heapMiB * 1024L * 1024;
            assertTrue(heap >= limit * 9 / 10 && heap <= limit, "actual worker heap must match the configured limit");
        }
        return workers;
    }

    private void testNgFixture() throws IOException {
        write("settings.gradle", "rootProject.name = 'testng-fixture'\n");
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'io.github.sjacek.shardpilot'
                }
                repositories { mavenCentral() }
                dependencies { testImplementation 'org.testng:testng:7.12.0' }
                test {
                    useTestNG {
                        parallel = 'none'
                        threadCount = 1
                    }
                    maxHeapSize = '256m'
                    shardPilot {
                        enabled = true
                        durationsFile = layout.projectDirectory.file('durations.tsv')
                    }
                }
                """);
        writeProperties();
        write("src/test/java/example/AlphaTest.java", """
                package example;
                import org.testng.annotations.BeforeClass;
                import org.testng.annotations.DataProvider;
                import org.testng.annotations.Test;
                import static org.testng.Assert.*;
                public class AlphaTest {
                    private boolean initialized;
                    @BeforeClass public void initialize() { initialized = true; }
                    @DataProvider(name = "codes", parallel = false, propagateFailureAsTestFailure = true)
                    public static Object[][] codes() { return new Object[][] {{"first"}, {"second"}}; }
                    @Test(dataProvider = "codes") public void row(String code) {
                        assertTrue(initialized);
                        assertFalse(code.isBlank());
                    }
                    @Test public void ordinary() { assertTrue(initialized); }
                }
                """);
        write("src/test/java/example/EchoTest.java", """
                package example;
                public class EchoTest {
                    @org.testng.annotations.Test public void echo() { }
                }
                """);
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
        // Pin the daemon to the test JVM as well as the distribution; an inherited JAVA_HOME must
        // not silently turn a JDK 17/21 matrix cell into another run on the developer's JDK 25.
        options.add("-Dorg.gradle.java.home=" + System.getProperty("java.home"));
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
                .withGradleVersion(consumerGradleVersion()).withArguments(options);
    }

    private static String consumerGradleVersion() {
        return System.getProperty("shardpilot.test.gradleVersion", GradleVersion.current().getVersion());
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
