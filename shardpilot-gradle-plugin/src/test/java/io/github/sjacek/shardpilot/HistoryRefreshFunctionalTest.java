package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
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
import java.util.Set;
import static org.gradle.testkit.runner.TaskOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sequential real builds verify cache contracts, producer isolation and both supported DSLs. */
class HistoryRefreshFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void defaultReportsDoNotScheduleTestOrCompilationProducers() throws Exception {
        fixture();
        xml("build/test-results/test/TEST-a.xml", 1);
        history("pruning = ShardHistoryOptions.Pruning.DISABLED");
        BuildResult result = run("refreshTestShardDurations");
        noProducers(result);
        assertEquals(SUCCESS, refreshOutcome(result));
        assertTrue(json().contains("\"pruning\":\"disabled\""));
    }

    @Test
    void reusesConfigurationAndBuildCacheWithoutProducers() throws Exception {
        fixture(); inputs();
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes'))");
        BuildResult first = run("refreshTestShardDurations", "--build-cache");
        noProducers(first);
        String expected = Files.readString(output());
        String report = json();
        BuildResult reused = run("refreshTestShardDurations", "--build-cache");
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertEquals(UP_TO_DATE, refreshOutcome(reused));
        BuildResult cached = run("clean", "refreshTestShardDurations", "--build-cache");
        noProducers(cached);
        assertEquals(FROM_CACHE, refreshOutcome(cached));
        assertEquals(expected, Files.readString(output())); assertEquals(report, json());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void verificationConventionIsLazyInEitherBlockOrder(boolean historyFirst) throws Exception {
        fixture(); inputs();
        write("plan/baseline.yaml", "schemaVersion: 1\nclasses:\n  - example.AlphaTest\n");
        if (historyFirst) history("reports.setFrom(files('downloaded'))");
        verification();
        if (!historyFirst) history("reports.setFrom(files('downloaded'))");
        BuildResult result = run("refreshTestShardDurations");
        noProducers(result);
        assertNull(result.task(":prepareTestShardManifests"));
        assertTrue(json().contains("\"shardCount\":3"));
        assertTrue(json().contains("\"eligible\":1"));
        assertTrue(json().contains("\"pruning\":\"baseline\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline", "classes", "disabled"})
    void explicitAutoSourceOverridesMissingVerificationBaseline(String source) throws Exception {
        fixture(); inputs(); verification();
        write("explicit.yaml", "schemaVersion: 1\nclasses:\n  - example.AlphaTest\n");
        history("reports.setFrom(files('downloaded')); " + (source.equals("baseline")
                ? "baselineFile = layout.projectDirectory.file('explicit.yaml')"
                : source.equals("classes") ? "classDirectories.from(files('classes'))"
                : "pruning = ShardHistoryOptions.Pruning.DISABLED"));
        noProducers(run("refreshTestShardDurations"));
        assertTrue(json().contains("\"pruning\":\"" + (source.equals("baseline") ? "baseline" : source.equals("classes") ? "classDirectories" : "disabled") + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BASELINE", "CLASS_DIRECTORIES", "DISABLED"})
    void acceptsEveryExplicitPruningMode(String mode) throws Exception {
        fixture(); inputs();
        write("baseline.yaml", "schemaVersion: 1\nclasses:\n  - example.AlphaTest\n");
        history("reports.setFrom(files('downloaded')); pruning = ShardHistoryOptions.Pruning." + mode + "; "
                + switch (mode) {
                    case "BASELINE" -> "baselineFile = layout.projectDirectory.file('baseline.yaml')";
                    case "CLASS_DIRECTORIES" -> "classDirectories.from(files('classes'))";
                    default -> "";
                });
        noProducers(run("refreshTestShardDurations"));
        assertTrue(json().contains("\"status\":\"PASSED\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "CLASS_DIRECTORIES"})
    void fallsBackToExistingOwningTaskClassDirectoriesWithoutRecompiling(String mode) throws Exception {
        fixture();
        run("testClasses"); // The caller deliberately prepares bytecode in an earlier invocation.
        xml("downloaded/TEST-a.xml", 1);
        history("reports.setFrom(files('downloaded')); pruning = ShardHistoryOptions.Pruning." + mode);
        noProducers(run("refreshTestShardDurations"));
        assertTrue(json().contains("\"eligible\":3"));
        assertTrue(json().contains("\"pruning\":\"classDirectories\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "baselineFile = layout.projectDirectory.file('baseline.yaml'); classDirectories.from(files('classes'))",
            "pruning = ShardHistoryOptions.Pruning.BASELINE",
            "pruning = ShardHistoryOptions.Pruning.BASELINE; baselineFile = layout.projectDirectory.file('baseline.yaml'); classDirectories.from(files('classes'))",
            "pruning = ShardHistoryOptions.Pruning.CLASS_DIRECTORIES; baselineFile = layout.projectDirectory.file('baseline.yaml')",
            "pruning = ShardHistoryOptions.Pruning.DISABLED; baselineFile = layout.projectDirectory.file('baseline.yaml')",
            "pruning = ShardHistoryOptions.Pruning.DISABLED; classDirectories.from(files('classes'))",
            "pruning = ShardHistoryOptions.Pruning.DISABLED; minimumCoverage = 0.5",
            "precision = 0; minimumSeconds = 0.1",
            "outputFile = layout.projectDirectory.file('durations.tsv')",
            "reportFile = outputFile"
    })
    void rejectsInvalidConfigurationWithoutJsonOrSourceChanges(String configuration) throws Exception {
        fixture(); inputs();
        String original = Files.readString(directory.resolve("durations.tsv"));
        history("reports.setFrom(files('downloaded')); " + configuration);
        noProducers(runner("refreshTestShardDurations").buildAndFail());
        assertFalse(Files.exists(report()));
        assertEquals(original, Files.readString(directory.resolve("durations.tsv")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "duplicate", "skipped", "invalidXml", "coverage",
            "invalidHistory", "invalidBaseline", "missingClasses"})
    void dataFailureWritesJsonAndRemovesStaleCandidate(String failure) throws Exception {
        fixture(); inputs();
        write("build/shardpilot/test/durations.tsv", "STALE CANDIDATE\n");
        String extra = "classDirectories.from(files('classes'))";
        switch (failure) {
            case "missing" -> extra = "baselineFile = layout.projectDirectory.file('baseline.yaml')";
            case "empty", "invalidBaseline" -> {
                extra = "baselineFile = layout.projectDirectory.file('baseline.yaml')";
                write("baseline.yaml", failure.equals("empty") ? "schemaVersion: 1\nclasses: []\n"
                        : "schemaVersion: 1\nclasses: [DO_NOT_LOG_THIS/invalid]\n");
            }
            case "duplicate" -> xml("downloaded/TEST-duplicate.xml", 2);
            case "skipped" -> write("downloaded/TEST-a.xml", "<testsuite name='example.AlphaTest' tests='1' skipped='1' time='0.1'/>");
            case "invalidXml" -> write("downloaded/TEST-z.xml", "<testsuite name='example.Bad' time='DO_NOT_LOG_THIS'/>");
            case "invalidHistory" -> write("durations.tsv", "example.AlphaTest\tDO_NOT_LOG_THIS\n");
            case "missingClasses" -> extra = "classDirectories.from(files('missing-classes'))";
            case "coverage" -> {
                write("classes/example/BetaTest.class", "");
                extra += "; minimumCoverage = 1.0";
            }
            default -> throw new IllegalArgumentException("Unknown failure fixture: " + failure);
        }
        history("reports.setFrom(files('downloaded')); " + extra);
        BuildResult result = runner("refreshTestShardDurations", "--build-cache").buildAndFail();
        noProducers(result);
        assertFalse(Files.exists(output()), result.getOutput());
        assertTrue(json().contains("\"status\":\"FAILED\""));
        assertFalse(json().contains("DO_NOT_LOG_THIS"));
        assertFalse(result.getOutput().contains("DO_NOT_LOG_THIS"));
        String expectedReason = switch (failure) {
            case "missing", "invalidBaseline", "missingClasses" -> "INVALID_BASELINE";
            case "empty" -> "EMPTY_ELIGIBILITY";
            case "duplicate" -> "DUPLICATE_SUITE";
            case "skipped" -> "NO_MEASUREMENTS";
            case "invalidXml" -> "INVALID_XML";
            case "invalidHistory" -> "INVALID_HISTORY";
            default -> "MINIMUM_COVERAGE";
        };
        assertTrue(json().contains("\"reason\":\"" + expectedReason + "\""));
        if (failure.equals("invalidXml")) assertTrue(json().contains("\"measured\":1"));
        assertEquals(FAILED, refreshOutcome(result));
    }

    @Test
    void protectsHistoryWhenAnOtherwiseValidResultWasAlreadyCached() throws Exception {
        fixture(); inputs();
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes'))");
        run("refreshTestShardDurations", "--build-cache");
        String original = Files.readString(directory.resolve("durations.tsv"));
        append("tasks.named('refreshTestShardDurations') { outputFile = layout.projectDirectory.file('durations.tsv') }\n");
        runner("refreshTestShardDurations", "--build-cache").buildAndFail();
        assertEquals(original, Files.readString(directory.resolve("durations.tsv")));
    }

    @Test
    void explicitTestOutputProvidersAreAlsoDetachedFromProducerDependencies() throws Exception {
        fixture();
        xml("build/test-results/test/TEST-a.xml", 1);
        history("reports.setFrom(tasks.named('test')); pruning = ShardHistoryOptions.Pruning.DISABLED");
        noProducers(run("refreshTestShardDurations"));
        assertTrue(json().contains("\"measured\":1"));
    }

    @Test
    void aLaterDataFailureReplacesPreviousSuccessEvidenceWithoutCachingFailure() throws Exception {
        fixture(); inputs();
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes'))");
        run("refreshTestShardDurations", "--build-cache");
        assertTrue(Files.exists(output())); assertTrue(json().contains("PASSED"));
        write("downloaded/TEST-a.xml", "<testsuite name='example.AlphaTest' tests='0' time='0'/>");
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = runner("refreshTestShardDurations", "--build-cache").buildAndFail();
            assertEquals(FAILED, refreshOutcome(result));
            assertFalse(Files.exists(output())); assertTrue(json().contains("FAILED"));
        }
    }

    @Test
    void customTestTaskGetsItsOwnNamespaceAndOrderingWithoutSchedulingIt() throws Exception {
        fixture(); inputs();
        append("""
                tasks.register('customIntegration', Test) { testClassesDirs = files('classes') }
                tasks.named('customIntegration', Test).get().shardPilot.history { reports.setFrom(files('downloaded')) }
                """);
        var result = run("refreshCustomIntegrationShardDurations");
        assertNull(result.task(":customIntegration")); noProducers(result);
        assertTrue(Files.isRegularFile(directory.resolve("build/shardpilot/customIntegration/durations.tsv")));
        assertFalse(Files.exists(output()));
    }

    @Test
    void runsThroughKotlinDsl() throws Exception {
        fixture(); inputs();
        Files.delete(directory.resolve("build.gradle"));
        write("build.gradle.kts", """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                import io.github.sjacek.shardpilot.ShardHistoryOptions
                import io.github.sjacek.shardpilot.DurationHistoryRefresh
                plugins { java; id("io.github.sjacek.shardpilot") }
                val settings = tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>()
                settings.durationsFile.set(layout.projectDirectory.file("durations.tsv"))
                settings.history {
                    reports.setFrom(files("downloaded"))
                    classDirectories.from(files("classes"))
                    pruning.set(ShardHistoryOptions.Pruning.CLASS_DIRECTORIES)
                    duplicates.set(DurationHistoryRefresh.Duplicates.FAIL)
                }
                """);
        noProducers(run("refreshTestShardDurations"));
        assertEquals(Map.of("example.AlphaTest", 1.0), DurationHistory.parse(Files.readString(output())));
    }

    @Test
    void acceptsGroovyDecimalLiteralsForMinimumAndCoverage() throws Exception {
        fixture(); inputs();
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes')); minimumSeconds = 2.0; precision = 0; minimumCoverage = 0.75");
        noProducers(run("refreshTestShardDurations"));
        assertEquals(Map.of("example.AlphaTest", 2.0), DurationHistory.parse(Files.readString(output())));
    }

    @Test
    void noHistoryBlockAddsNoRefreshTaskAndASecondBlockIsRejected() throws Exception {
        fixture();
        assertFalse(run("tasks", "--all").getOutput().contains("refreshTestShardDurations"));
        history("pruning = ShardHistoryOptions.Pruning.DISABLED");
        history("pruning = ShardHistoryOptions.Pruning.DISABLED");
        assertTrue(runner("tasks").buildAndFail().getOutput().contains("history is already configured"));
    }

    @Test
    void twoRealShardsRefreshHistoryAndChangeTheNextPlan() throws Exception {
        fixture();
        append("""
                tasks.named('test', Test).get().shardPilot.verification {
                    eligibleClasses.from(sourceSets.test.output.classesDirs.asFileTree.matching { include '**/*Test.class' })
                    shardCount = 2
                }
                """);
        history("reports.setFrom(files('downloaded/one', 'downloaded/two'))");
        run("prepareTestShardManifests");
        Path plan = directory.resolve("build/shardpilot/test/plan/planned-1.yaml");
        Set<String> before = ClassManifest.read(plan, false);
        assertTrue(before.contains("example.GammaTest"));
        for (int shard = 1; shard <= 2; shard++) {
            run("test", "-Pshardpilot.count=2", "-Pshardpilot.index=" + shard);
            Path target = directory.resolve(shard == 1 ? "downloaded/one" : "downloaded/two");
            Files.createDirectories(target);
            try (var files = Files.list(directory.resolve("build/test-results/test"))) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        noProducers(run("refreshTestShardDurations"));
        assertEquals(3, DurationHistory.parse(Files.readString(output())).size());
        assertTrue(json().contains("\"coverage\":1.0"));
        Files.copy(output(), directory.resolve("durations.tsv"), StandardCopyOption.REPLACE_EXISTING);
        run("prepareTestShardManifests");
        Set<String> after = ClassManifest.read(plan, false);
        assertNotEquals(before, after);
        assertTrue(after.contains("example.AlphaTest"));
        assertFalse(after.contains("example.GammaTest"));
    }

    @Test
    void changedXmlHistoryAndBytecodeInvalidateCachedResultsIndependently() throws Exception {
        fixture(); inputs();
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes'))");
        noProducers(run("refreshTestShardDurations", "--build-cache"));

        // Same paths, changed XML content: a previous success must not mask the new measured time.
        xml("downloaded/TEST-a.xml", 2);
        BuildResult changedReport = run("refreshTestShardDurations", "--build-cache");
        noProducers(changedReport);
        assertEquals(SUCCESS, refreshOutcome(changedReport));
        assertEquals(Map.of("example.AlphaTest", 2.0), DurationHistory.parse(Files.readString(output())));

        // Only previous history changes: report the newly pruned old record even though TSV is unchanged.
        Files.writeString(directory.resolve("durations.tsv"), "example.Kept\t3.0\n", java.nio.file.StandardOpenOption.APPEND);
        BuildResult changedHistory = run("refreshTestShardDurations", "--build-cache");
        noProducers(changedHistory);
        assertEquals(SUCCESS, refreshOutcome(changedHistory));
        assertTrue(json().contains("\"dropped\":3"));

        // Only eligibility bytecode changes: the previously foreign old record is now retained.
        write("classes/example/Kept.class", "");
        BuildResult changedClasses = run("refreshTestShardDurations", "--build-cache");
        noProducers(changedClasses);
        assertEquals(SUCCESS, refreshOutcome(changedClasses));
        assertEquals(Map.of("example.AlphaTest", 2.0, "example.Kept", 3.0), DurationHistory.parse(Files.readString(output())));
        assertTrue(json().contains("\"eligible\":2")); assertTrue(json().contains("\"kept\":1"));
    }

    @Test
    void changedBaselineContentInvalidatesCacheWithoutPreparingAnotherPlan() throws Exception {
        fixture(); inputs();
        write("baseline.yaml", "schemaVersion: 1\nclasses: [example.AlphaTest]\n");
        history("reports.setFrom(files('downloaded')); baselineFile = layout.projectDirectory.file('baseline.yaml')");
        run("refreshTestShardDurations", "--build-cache");
        write("baseline.yaml", "schemaVersion: 1\nclasses: [example.AlphaTest, example.GammaTest]\n");
        var result = run("refreshTestShardDurations", "--build-cache");
        noProducers(result); assertNull(result.task(":prepareTestShardManifests"));
        assertEquals(SUCCESS, refreshOutcome(result));
        assertEquals(Map.of("example.AlphaTest", 1.0, "example.GammaTest", 100.0), DurationHistory.parse(Files.readString(output())));
        assertTrue(json().contains("\"coverage\":0.5"));
    }

    @Test
    void cacheArtifactsCanBeRestoredInAnotherCheckoutDirectory() throws Exception {
        Path root = directory;
        Path sharedCache = root.resolve("shared-cache");
        directory = root.resolve("first-checkout");
        fixture(); inputs();
        String configuration = "reports.setFrom(files('downloaded')); classDirectories.from(files('classes'))";
        history(configuration);
        String cacheSettings = "\nbuildCache { local { directory = file(providers.gradleProperty('sharedCache').get()) } }\n";
        Files.writeString(directory.resolve("settings.gradle"), cacheSettings, java.nio.file.StandardOpenOption.APPEND);
        String cacheArgument = "-PsharedCache=" + sharedCache;
        run("refreshTestShardDurations", "--build-cache", cacheArgument);
        String tsv = Files.readString(output()); String report = json();

        directory = root.resolve("second-checkout");
        fixture(); inputs(); history(configuration);
        Files.writeString(directory.resolve("settings.gradle"), cacheSettings, java.nio.file.StandardOpenOption.APPEND);
        var restored = run("refreshTestShardDurations", "--build-cache", cacheArgument);
        noProducers(restored);
        assertEquals(FROM_CACHE, refreshOutcome(restored));
        assertEquals(tsv, Files.readString(output())); assertEquals(report, json());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reportHistory", "outputBaseline", "reportBaseline"})
    void protectsBothVersionedInputsFromEitherOutputAlias(String alias) throws Exception {
        fixture(); inputs();
        String baseline = "schemaVersion: 1\nclasses: [example.AlphaTest]\n";
        write("baseline.yaml", baseline);
        String original = Files.readString(directory.resolve("durations.tsv"));
        history("reports.setFrom(files('downloaded')); baselineFile = layout.projectDirectory.file('baseline.yaml'); " + switch (alias) {
            case "reportHistory" -> "reportFile = layout.projectDirectory.file('durations.tsv')";
            case "outputBaseline" -> "outputFile = layout.projectDirectory.file('baseline.yaml')";
            default -> "reportFile = layout.projectDirectory.file('baseline.yaml')";
        });
        var result = runner("refreshTestShardDurations", "--build-cache").buildAndFail();
        noProducers(result);
        assertTrue(result.getOutput().contains("cannot overwrite durationsFile or baselineFile"));
        assertEquals(original, Files.readString(directory.resolve("durations.tsv")));
        assertEquals(baseline, Files.readString(directory.resolve("baseline.yaml")));
    }

    @Test
    void aDifferentFilenameHardLinkedToHistoryIsStillProtected() throws Exception {
        fixture(); inputs();
        Path original = directory.resolve("durations.tsv");
        String previous = Files.readString(original);
        Path alias = directory.resolve("history-alias.tsv");
        Files.createLink(alias, original);
        history("reports.setFrom(files('downloaded')); classDirectories.from(files('classes')); outputFile = layout.projectDirectory.file('history-alias.tsv')");
        var result = runner("refreshTestShardDurations").buildAndFail();
        noProducers(result);
        assertTrue(result.getOutput().contains("cannot overwrite durationsFile or baselineFile"));
        assertEquals(previous, Files.readString(original)); assertEquals(previous, Files.readString(alias));
        assertFalse(Files.exists(report()));
    }

    private void fixture() throws IOException {
        write("settings.gradle", "rootProject.name = 'history-fixture'\nbuildCache { local { directory = file('cache') } }\n");
        write("gradle.properties", "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\n");
        write("build.gradle", """
                import io.github.sjacek.shardpilot.ShardHistoryOptions
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
                """);
        write("durations.tsv", "example.AlphaTest\t1.0\nexample.BetaTest\t1.0\nexample.GammaTest\t100.0\n");
        for (String name : List.of("Alpha", "Beta", "Gamma")) {
            write("src/test/java/example/" + name + "Test.java", "package example; class " + name
                    + "Test { @org.junit.jupiter.api.Test void works() throws Exception { Thread.sleep("
                    + (name.equals("Alpha") ? "700" : "50") + "); } }\n");
        }
    }

    private void inputs() throws IOException {
        write("classes/example/AlphaTest.class", "");
        xml("downloaded/TEST-a.xml", 1);
    }

    private void xml(String path, double time) throws IOException {
        write(path, "<testsuite name='example.AlphaTest' tests='1' time='" + time + "'/>");
    }

    private void history(String configuration) throws IOException { append("tasks.named('test', Test).get().shardPilot.history { " + configuration + " }\n"); }

    private void verification() throws IOException { append("tasks.named('test', Test).get().shardPilot.verification { eligibleClasses.from(fileTree('classes')); plannedDirectory = layout.projectDirectory.dir('plan'); shardCount = 3 }\n"); }

    private Path output() { return directory.resolve("build/shardpilot/test/durations.tsv"); }

    private Path report() { return directory.resolve("build/shardpilot/test/durations.json"); }

    private String json() throws IOException { return Files.readString(report()); }

    private static org.gradle.testkit.runner.TaskOutcome refreshOutcome(BuildResult result) {
        return java.util.Objects.requireNonNull(result.task(":refreshTestShardDurations"),
                "Expected refresh task was not scheduled").getOutcome();
    }

    private void noProducers(BuildResult result) {
        assertNull(result.task(":test")); assertNull(result.task(":compileJava")); assertNull(result.task(":compileTestJava"));
        assertNull(result.task(":testClasses"));
    }

    private void write(String path, String contents) throws IOException {
        Path file = directory.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, contents);
    }

    private void append(String contents) throws IOException { Files.writeString(directory.resolve("build.gradle"), contents, java.nio.file.StandardOpenOption.APPEND); }

    private BuildResult run(String... arguments) { return runner(arguments).build(); }

    private GradleRunner runner(String... arguments) {
        var args = new ArrayList<>(List.of(arguments));
        args.addAll(List.of("--console=plain", "--stacktrace", "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(args);
    }
}
