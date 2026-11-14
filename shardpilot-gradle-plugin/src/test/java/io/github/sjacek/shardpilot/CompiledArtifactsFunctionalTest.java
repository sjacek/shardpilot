package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real workers and cache reuse prove that C3 is a pre-execution gate, not merely a callable utility. */
class CompiledArtifactsFunctionalTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void groovyAndKotlinSealAndVerifyWithoutSchedulingProducersAndAlwaysRecheck(boolean kotlin) throws Exception {
        fixture(kotlin); compileAndSeal();
        var first = runner("verifyTestCompiledArtifacts").build(); noProducers(first);
        var second = runner("verifyTestCompiledArtifacts").build(); noProducers(second);
        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.SUCCESS, outcome(second, ":verifyTestCompiledArtifacts"));
        // Same-size corruption cannot pass after Configuration Cache restoration.
        Files.writeString(directory.resolve("build/resources/test/config.txt"), "other");
        var failed = runner("verifyTestCompiledArtifacts").buildAndFail(); noProducers(failed);
        assertTrue(failed.getOutput().contains("FILE_CONTENT_MISMATCH"));
        assertFalse(Files.exists(directory.resolve("native-ran.txt")));
    }

    @ParameterizedTest @ValueSource(strings = {"changed", "extra", "missing", "revision", "digest", "no-sources"})
    void aNativeTestWorkerCannotStartWithCorruptDownloadedArtifacts(String corruption) throws Exception {
        fixture(false); compileAndSeal();
        var successful = runner("test", "-Pprecompiled").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(successful, ":test"));
        assertTrue(Files.exists(directory.resolve("native-ran.txt"))); Files.delete(directory.resolve("native-ran.txt"));
        switch (corruption) {
            case "extra" -> Files.writeString(directory.resolve("build/classes/java/test/Stale.class"), "stale");
            case "missing" -> Files.delete(directory.resolve("build/resources/test/config.txt"));
            case "no-sources" -> Files.delete(directory.resolve("build/classes/java/test/example/ExampleTest.class"));
            case "revision", "digest" -> { /* Keep valid bytes: only independently supplied expectations change. */ }
            default -> Files.writeString(directory.resolve("build/resources/test/config.txt"), "other");
        }
        var command = new ArrayList<>(List.of("test", "-Pprecompiled"));
        if (corruption.equals("revision")) command.add("-Pshardpilot.compiledRevision=" + "b".repeat(40));
        if (corruption.equals("digest")) command.add("-Pshardpilot.compiledManifestSha256=" + "0".repeat(64));
        var failed = runner(command.toArray(String[]::new)).buildAndFail();
        assertEquals(TaskOutcome.FAILED, outcome(failed, ":verifyTestCompiledArtifacts"));
        assertNull(failed.task(":test"));
        assertFalse(Files.exists(directory.resolve("native-ran.txt")), "C3 must fail before a JVM can write its marker");
    }

    @Test void aSecondDslDeclarationCannotSilentlyReplaceTheTrustedRequirements() throws Exception {
        fixture(false);
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle")) + """
                tasks.named('test', Test).get().shardPilot.compiledArtifacts { roots = [] }
                """);
        var failed = runner("tasks", "--all").buildAndFail(); noProducers(failed);
        assertTrue(failed.getOutput().contains("compiled artifacts are already configured"));
        assertFalse(Files.exists(directory.resolve("build/shardpilot/test/compiled-artifacts.json")));
    }

    @Test void theFinalWorkerGuardDetectsMutationByAnotherExplicitlyScheduledTask() throws Exception {
        fixture(false); compileAndSeal();
        Files.writeString(directory.resolve("build.gradle"), Files.readString(directory.resolve("build.gradle")) + """
                tasks.register('mutate') {
                    dependsOn 'verifyTestCompiledArtifacts'
                    def target = layout.buildDirectory.file('resources/test/config.txt')
                    doLast { target.get().asFile.text = 'other' }
                }
                tasks.named('test') { dependsOn 'mutate' }
                """);
        var failed = runner("test", "-Pprecompiled").buildAndFail();
        assertEquals(TaskOutcome.SUCCESS, outcome(failed, ":verifyTestCompiledArtifacts"));
        assertEquals(TaskOutcome.FAILED, outcome(failed, ":test"));
        assertTrue(failed.getOutput().contains("FILE_CONTENT_MISMATCH"));
        assertFalse(Files.exists(directory.resolve("native-ran.txt")));
    }

    @Test void standaloneTasksWorkWithoutJavaAndCannotRestoreAStaleBuildCacheSuccess() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c3-standalone'\n");
        Files.createDirectories(directory.resolve("downloaded")); Files.writeString(directory.resolve("downloaded/Test.class"), "one");
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'io.github.sjacek.shardpilot' }
                tasks.register('seal', io.github.sjacek.shardpilot.SealCompiledArtifacts) {
                    options.revision = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                    options.suite = 'example'
                    options.roots = ['downloaded']
                }
                tasks.register('verify', io.github.sjacek.shardpilot.VerifyCompiledArtifacts) {
                    options.revision = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                    options.suite = 'example'
                    options.roots = ['downloaded']
                    outputs.cacheIf { true }
                }
                """);
        runner("seal").build(); runner("verify", "--build-cache").build();
        Files.writeString(directory.resolve("downloaded/Test.class"), "two");
        assertTrue(runner("verify", "--build-cache").buildAndFail().getOutput().contains("FILE_CONTENT_MISMATCH"));
    }

    @Test void missingManifestCannotTriggerCompilationOrRepair() throws Exception {
        fixture(false);
        var failed = runner("verifyTestCompiledArtifacts").buildAndFail(); noProducers(failed);
        assertFalse(Files.exists(directory.resolve("build/classes")));
        assertFalse(Files.exists(directory.resolve("build/shardpilot/test/compiled-artifacts.json")));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void cyclicLinksFailBeforeGradleSnapshotsTheNativeTestInputs() throws Exception {
        fixture(false); compileAndSeal();
        Path root = directory.resolve("build/resources/test"); Files.createSymbolicLink(root.resolve("cycle"), root);
        var failed = runner("test", "-Pprecompiled").buildAndFail();
        assertTrue(failed.getOutput().contains("UNSAFE_ARTIFACT_PATH")); assertNull(failed.task(":test"));
    }

    @Test void pluginApplicationAloneDoesNotRegisterAnyC3Task() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c3-opt-in'\n");
        Files.writeString(directory.resolve("build.gradle"), "plugins { id 'java'; id 'io.github.sjacek.shardpilot' }\n");
        assertFalse(runner("tasks", "--all").build().getOutput().contains("CompiledArtifacts"));
    }

    private void compileAndSeal() {
        runner("testClasses").build(); noProducers(runner("sealTestCompiledArtifacts").build());
    }

    private void fixture(boolean kotlin) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c3-worker'\n");
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx256m\n");
        String build = kotlin ? """
                import io.github.sjacek.shardpilot.ShardPilotExtension
                plugins { java; id("io.github.sjacek.shardpilot") }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
                }
                tasks.named<Test>("test").get().extensions.getByType<ShardPilotExtension>().compiledArtifacts {
                    roots.set(listOf("build/classes", "build/resources"))
                    requiredDirectories.set(listOf("build/classes/java/test"))
                    requiredFiles.set(listOf("build/resources/test/config.txt"))
                }
                tasks.named<Test>("test") { useJUnitPlatform() }
                val precompiled = providers.gradleProperty("precompiled").isPresent
                tasks.withType<JavaCompile>().configureEach { enabled = !precompiled }
                tasks.withType<ProcessResources>().configureEach { enabled = !precompiled }
                """ : """
                plugins { id 'java'; id 'io.github.sjacek.shardpilot' }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation platform('org.junit:junit-bom:6.1.3')
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:6.1.3'
                }
                tasks.named('test', Test).get().shardPilot.compiledArtifacts {
                    roots = ['build/classes', 'build/resources']
                    requiredDirectories = ['build/classes/java/test']
                    requiredFiles = ['build/resources/test/config.txt']
                }
                tasks.named('test') { useJUnitPlatform() }
                def precompiled = providers.gradleProperty('precompiled').isPresent()
                tasks.withType(JavaCompile).configureEach { enabled = !precompiled }
                tasks.withType(ProcessResources).configureEach { enabled = !precompiled }
                """;
        Files.writeString(directory.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), build);
        Path source = directory.resolve("src/test/java/example/ExampleTest.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                import org.junit.jupiter.api.Test;
                public class ExampleTest {
                    @Test void worker() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of("native-ran.txt"), "executed"); }
                }
                """);
        Path resource = directory.resolve("src/test/resources/config.txt"); Files.createDirectories(resource.getParent()); Files.writeString(resource, "valid");
    }

    private org.gradle.testkit.runner.GradleRunner runner(String... tasks) {
        var arguments = new ArrayList<>(List.of("-Pshardpilot.compiledRevision=" + "a".repeat(40)));
        arguments.addAll(List.of(tasks)); arguments.addAll(List.of("--offline", "--console=plain", "--stacktrace"));
        return VerifiedTestKit.create(directory).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion())).withArguments(arguments);
    }
    private static TaskOutcome outcome(BuildResult result, String path) { var task = result.task(path); assertNotNull(task, result.getOutput()); return task.getOutcome(); }
    private static void noProducers(BuildResult result) {
        assertTrue(result.getTasks().stream().noneMatch(t -> t.getPath().matches(".*(compile.*|process.*Resources|testClasses|test|prepare.*)")), result.getOutput());
    }
}
