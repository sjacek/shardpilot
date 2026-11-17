package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real managed DSL and Configuration Cache checks for the artifact-only C5 → C2/C3 bridge. */
class ExecutionSelectionFunctionalTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothDslsReplayFrozenSelectionAndVerifyItsOriginalV1NodeEvidence(boolean kotlin) throws Exception {
        fixture(kotlin);
        var first = runner("prepareTestExecutionSelection", "verifyTestExecutionEvidence").build();
        assertOutcome(first, ":prepareTestExecutionSelection", TaskOutcome.SUCCESS);
        assertOutcome(first, ":verifyTestExecutionEvidence", TaskOutcome.SUCCESS);
        assertEquals(Files.readString(directory.resolve("retained/selection.json")), Files.readString(output()));
        String report = Files.readString(report());
        assertTrue(report.contains("\"selectionProvenance\"")); assertTrue(report.contains("\"schemaVersion\":2"));
        var second = runner("prepareTestExecutionSelection", "verifyTestExecutionEvidence").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertOutcome(second, ":prepareTestExecutionSelection", TaskOutcome.SUCCESS);
        assertOutcome(second, ":verifyTestExecutionEvidence", TaskOutcome.SUCCESS);
        assertEquals(report, Files.readString(report()));
        noProducers(first); noProducers(second);
    }

    @Test
    void changedCompiledBytesFailBothGatesAfterConfigurationCacheReuse() throws Exception {
        fixture(false);
        runner("prepareTestExecutionSelection", "verifyTestExecutionEvidence").build();
        Files.writeString(directory.resolve("retained/compiled/classes/synthetic-testdata.txt"), "PRIVATE_CHANGED");
        var producer = runner("prepareTestExecutionSelection").buildAndFail();
        assertTrue(producer.getOutput().contains("Reusing configuration cache.") || producer.getOutput().contains("Configuration cache entry stored."));
        assertFalse(Files.exists(output()), "a stale executable selection must not survive a failed replay");
        noProducers(producer);
        var aggregate = runner("verifyTestExecutionEvidence").buildAndFail();
        noProducers(aggregate);
        assertTrue(Files.readString(report()).contains("COMPILED_ARTIFACT_MISMATCH"));
        assertTrue(Files.readString(report()).contains("\"selectionProvenance\":null"));
        assertFalse(Files.readString(report()).contains("PRIVATE"));
    }

    @Test
    void optionalCompiledConfigurationCannotBeDroppedFromAPinnedSelection() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                .replace(compiledGroovy(), ""));
        noProducers(runner("prepareTestExecutionSelection").build());
        assertTrue(Files.readString(output()).contains("\"compiledArtifactsSha256\":null"));
        noProducers(runner("verifyTestExecutionEvidence").buildAndFail());
        assertTrue(Files.readString(report()).contains("COMPILED_ARTIFACT_MISMATCH"));
    }

    @Test
    void artifactProducerAlsoWorksWithoutJavaPluginUsingCommonCommandLineParameters() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'io.github.sjacek.shardpilot' }
                tasks.register('bindSelection', io.github.sjacek.shardpilot.PrepareExecutionSelection) {
                    selectionFile = layout.projectDirectory.file('generated/selection.json')
                }
                """);
        var result = runner("bindSelection", "-Pshardpilot.selectionProfile=smoke-testdata",
                "-Pshardpilot.selectionSuite=profile-smoke-testdata", "-Pshardpilot.count=2",
                "-Pshardpilot.executionRevision=" + "a".repeat(40),
                "-Pshardpilot.selectionSnapshotDirectory=retained/snapshot",
                "-Pshardpilot.selectionPolicyFile=retained/policy.yaml",
                "-Pshardpilot.executionEligibilityFile=retained/baseline.yaml",
                "-Pshardpilot.executionHistoryFile=retained/history.tsv").build();
        assertOutcome(result, ":bindSelection", TaskOutcome.SUCCESS); noProducers(result);
        assertTrue(Files.readString(output()).contains("\"compiledArtifactsSha256\":null"));
    }

    @Test
    void outputOverlappingCompiledTreesIsRejectedWithoutModifyingInput() throws Exception {
        fixture(false);
        Path input = directory.resolve("retained/compiled/classes/synthetic-testdata.txt");
        String before = Files.readString(input);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                .replace("generated/selection.json", "retained/compiled/classes/synthetic-testdata.txt"));
        noProducers(runner("prepareTestExecutionSelection").buildAndFail());
        assertEquals(before, Files.readString(input));
    }

    @Test
    void ownerDslWithoutNativeSelectionKeepsTheProfileBasedDefaultSuite() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                .replace("profile = 'smoke-testdata'; suite = 'profile-smoke-testdata'", "profile = 'smoke-testdata'"));
        noProducers(runner("prepareTestExecutionSelection").build());
        assertEquals(Files.readString(directory.resolve("retained/selection.json")), Files.readString(output()));
    }

    @Test
    void inheritsFrozenNativeSelectionSettingsWithoutSchedulingItsProducers() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'java'; id 'io.github.sjacek.shardpilot' }
                def pilot = tasks.named('test', Test).get().shardPilot
                pilot.shardCount = 2
                pilot.durationsFile = layout.projectDirectory.file('retained/history.tsv')
                pilot.selection {
                    policyFile = layout.projectDirectory.file('retained/policy.yaml')
                    profile = 'smoke-testdata'
                    snapshotDirectory = layout.projectDirectory.dir('retained/snapshot')
                }
                pilot.executionSelection {
                    eligibilityFile = layout.projectDirectory.file('retained/baseline.yaml')
                    revision = 'REVISION'
                    selectionFile = layout.projectDirectory.file('generated/selection.json')
                }
                tasks.withType(JavaCompile).configureEach { doFirst { throw new GradleException('must not compile') } }
                tasks.withType(Test).configureEach { doFirst { throw new GradleException('must not execute tests') } }
                """.replace("REVISION", "a".repeat(40)));
        noProducers(runner("prepareTestExecutionSelection").build());
        assertTrue(Files.readString(output()).contains("\"profile\":\"smoke-testdata\""));
        assertTrue(Files.readString(output()).contains("\"compiledArtifactsSha256\":null"));
        var second = runner("prepareTestExecutionSelection").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        noProducers(second);
    }

    @Test
    void broadBuildCachingCannotRestoreSelectionAfterCompiledInputsHaveChanged() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                + "\ntasks.named('prepareTestExecutionSelection') { outputs.cacheIf { true } }\n");
        noProducers(runner("prepareTestExecutionSelection", "--build-cache").build());
        // Removing output makes a cached restoration attractive; current C3 bytes must still win.
        Files.delete(output()); Files.writeString(directory.resolve("retained/compiled/classes/synthetic-testdata.txt"), "PRIVATE_CHANGED");
        var result = runner("prepareTestExecutionSelection", "--build-cache").buildAndFail();
        assertOutcome(result, ":prepareTestExecutionSelection", TaskOutcome.FAILED); noProducers(result);
        assertTrue(result.getOutput().contains("COMPILED_ARTIFACT_MISMATCH"));
        assertFalse(result.getOutput().contains("PRIVATE_CHANGED")); assertFalse(Files.exists(output()));
    }

    @Test
    void equivalentTimingValuesWithDifferentRetainedBytesRequireTheOriginalSnapshotInput() throws Exception {
        fixture(false); noProducers(runner("prepareTestExecutionSelection").build());
        Path history = directory.resolve("retained/history.tsv"); String original = Files.readString(history);
        Files.writeString(history, original.replace("\t10\n", "\t10.0\n"));
        var rejected = runner("prepareTestExecutionSelection").buildAndFail(); noProducers(rejected);
        assertTrue(rejected.getOutput().contains("Reusing configuration cache."), rejected.getOutput());
        assertTrue(rejected.getOutput().contains("SELECTION_PROVENANCE_MISMATCH")); assertFalse(Files.exists(output()));
        // Recover by restoring original evidence, without replanning, compiling or resealing C3.
        Files.writeString(history, original); var recovered = runner("prepareTestExecutionSelection").build();
        noProducers(recovered); assertTrue(recovered.getOutput().contains("Reusing configuration cache."), recovered.getOutput());
        assertEquals(Files.readString(directory.resolve("retained/selection.json")), Files.readString(output()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepareTestExecutionSelection", "verifyTestExecutionEvidence"})
    void duplicateCompiledConfigurationFailsBeforeRetainedInputsAreTouched(String task) throws Exception {
        fixture(false); Path manifest = directory.resolve("retained/compiled-manifest.json"); String before = Files.readString(manifest);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle"))
                + "\ntasks.named('" + task + "') { compiledArtifacts { } }\n");
        var result = runner(task).buildAndFail(); noProducers(result);
        assertTrue(result.getOutput().contains("Compiled execution inputs are already configured"), result.getOutput());
        assertEquals(before, Files.readString(manifest)); assertFalse(Files.exists(output())); assertFalse(Files.exists(report()));
    }

    private void fixture(boolean kotlin) throws Exception {
        Path source = Path.of("docs/examples/execution-selection-testdata");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = directory.resolve("retained").resolve(source.relativize(file));
                Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='execution-selection-testdata'\n");
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx256m\n");
        String build = kotlin ? """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                plugins { java; id("io.github.sjacek.shardpilot") }
                val shardPilot = tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>()
                shardPilot.executionSelection {
                    snapshotDirectory.set(layout.projectDirectory.dir("retained/snapshot"))
                    policyFile.set(layout.projectDirectory.file("retained/policy.yaml"))
                    eligibilityFile.set(layout.projectDirectory.file("retained/baseline.yaml"))
                    historyFile.set(layout.projectDirectory.file("retained/history.tsv"))
                    profile.set("smoke-testdata"); suite.set("profile-smoke-testdata")
                    revision.set("REVISION"); shardCount.set(2)
                    selectionFile.set(layout.projectDirectory.file("generated/selection.json"))
                    COMPILED
                }
                shardPilot.executionEvidence {
                    expectedRequest.set(layout.projectDirectory.file("retained/expected.json"))
                    eligibilityFile.set(layout.projectDirectory.file("retained/baseline.yaml"))
                    selectionFile.set(layout.projectDirectory.file("retained/selection.json"))
                    nodesDirectory.set(layout.projectDirectory.dir("retained/nodes"))
                    COMPILED
                }
                tasks.withType<JavaCompile>().configureEach { doFirst { error("must not compile") } }
                tasks.withType<Test>().configureEach { doFirst { error("must not execute tests") } }
                """.replace("COMPILED", """
                    compiledArtifacts {
                        baseDirectory.set(layout.projectDirectory.dir("retained/compiled"))
                        manifestFile.set(layout.projectDirectory.file("retained/compiled-manifest.json"))
                        revision.set("REVISION"); suite.set("profile-smoke-testdata")
                        roots.set(listOf("classes")); requiredDirectories.set(listOf("classes"))
                        requiredFiles.set(listOf("classes/synthetic-testdata.txt"))
                    }
                """) : """
                plugins { id 'java'; id 'io.github.sjacek.shardpilot' }
                def pilot = tasks.named('test', Test).get().shardPilot
                pilot.executionSelection {
                    snapshotDirectory = layout.projectDirectory.dir('retained/snapshot')
                    policyFile = layout.projectDirectory.file('retained/policy.yaml')
                    eligibilityFile = layout.projectDirectory.file('retained/baseline.yaml')
                    historyFile = layout.projectDirectory.file('retained/history.tsv')
                    profile = 'smoke-testdata'; suite = 'profile-smoke-testdata'
                    revision = 'REVISION'; shardCount = 2
                    selectionFile = layout.projectDirectory.file('generated/selection.json')
                    COMPILED
                }
                pilot.executionEvidence {
                    expectedRequest = layout.projectDirectory.file('retained/expected.json')
                    eligibilityFile = layout.projectDirectory.file('retained/baseline.yaml')
                    selectionFile = layout.projectDirectory.file('retained/selection.json')
                    nodesDirectory = layout.projectDirectory.dir('retained/nodes')
                    COMPILED
                }
                tasks.withType(JavaCompile).configureEach { doFirst { throw new GradleException('must not compile') } }
                tasks.withType(Test).configureEach { doFirst { throw new GradleException('must not execute tests') } }
                """.replace("COMPILED", compiledGroovy());
        Files.writeString(directory.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), build.replace("REVISION", "a".repeat(40)));
    }

    private static String compiledGroovy() {
        return """
                    compiledArtifacts {
                        baseDirectory = layout.projectDirectory.dir('retained/compiled')
                        manifestFile = layout.projectDirectory.file('retained/compiled-manifest.json')
                        revision = 'REVISION'; suite = 'profile-smoke-testdata'
                        roots = ['classes']; requiredDirectories = ['classes']
                        requiredFiles = ['classes/synthetic-testdata.txt']
                    }
                """.replace("REVISION", "a".repeat(40));
    }

    private Path output() { return directory.resolve("generated/selection.json"); }

    private Path report() { return directory.resolve("build/shardpilot/test/execution-evidence.json"); }

    private static void noProducers(BuildResult result) {
        for (String name : List.of("test", "compileJava", "compileTestJava", "prepareTestShardManifests", "prepareTestSelection",
                "acquireTestGitChanges", "sealTestCompiledArtifacts")) assertNull(result.task(":" + name), name);
    }

    private static void assertOutcome(BuildResult result, String name, TaskOutcome expected) {
        var task = result.task(name); assertNotNull(task); assertEquals(expected, task.getOutcome());
    }

    private GradleRunner runner(String... requested) {
        var arguments = new ArrayList<>(List.of(requested));
        arguments.addAll(List.of("--offline", "--stacktrace", "--console=plain"));
        return VerifiedTestKit.create(directory).withPluginClasspath().withGradleVersion(System.getProperty("shardpilot.test.gradleVersion",
                org.gradle.util.GradleVersion.current().getVersion())).withArguments(arguments);
    }
}
