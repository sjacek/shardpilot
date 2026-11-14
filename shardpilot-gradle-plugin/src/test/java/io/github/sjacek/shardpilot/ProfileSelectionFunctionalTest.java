package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

/** Real native builds exercise CLI profiles, live Git reads and artifact-only multi-node aggregation. */
class ProfileSelectionFunctionalTest {
    @TempDir
    Path directory;

    @Test
    void cliAffectedProfileUsesGitAndReusesConfigurationCacheWithoutCachingMutableRefs() throws Exception {
        fixture();
        String[] ids = gitFixture();
        var first = runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata").build();
        assertEquals(TaskOutcome.SUCCESS, requireNonNull(first.task(":test")).getOutcome());
        assertEquals(Set.of("example.selection.AlphaTest", "example.selection.BetaTest"), actual("affected-testdata", 1));
        assertEquals(actual("affected-testdata", 1), executedAffectedClasses(1));
        var second = runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.SUCCESS, requireNonNull(second.task(":acquireTestGitChanges")).getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, requireNonNull(second.task(":test")).getOutcome());
        Files.writeString(directory.resolve(".git/refs/heads/main-testdata"), ids[1] + "\n");
        runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata").build();
        assertEquals(ids[1], GitChanges.read(output("affected-testdata", "selection/decision/git.json")).changes().baseRevision());
        Files.writeString(directory.resolve("src/modified.txt"), "dirty checkout\n");
        runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata").build();
        assertEquals(3, actual("affected-testdata", 1).size(), "unknown impact safely falls back to the full native suite");
        assertTrue(Files.readString(output("affected-testdata", "selection/decision/git.json")).contains("DIRTY_WORKTREE"));
    }

    @Test
    void frozenAffectedCohortRunsBothNodesAndAggregationCannotReacquireOrRegenerateEvidence() throws Exception {
        fixture();
        String[] ids = gitFixture();
        runner("prepareTestShardManifests", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata", "-Pshardpilot.count=2").build();
        Path frozen = directory.resolve("build/downloaded-selection");
        copy(output("affected-testdata", "selection"), frozen);
        String identity = Files.readString(frozen.resolve("selection.id"));
        // A worker must not query a moving base ref: only its exact HEAD and cleanliness matter.
        Files.writeString(directory.resolve(".git/refs/heads/main-testdata"), ids[1] + "\n");
        for (int index = 1; index <= 2; index++) {
            var run = runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.count=2", "-Pshardpilot.index=" + index,
                    "-Pshardpilot.selectionSnapshotDirectory=" + frozen).build();
            assertNull(run.task(":acquireTestGitChanges"));
            assertEquals(actual("affected-testdata", index), executedAffectedClasses(index));
        }
        var union = new java.util.TreeSet<>(actual("affected-testdata", 1));
        assertTrue(java.util.Collections.disjoint(union, actual("affected-testdata", 2)));
        union.addAll(actual("affected-testdata", 2));
        assertEquals(Set.of("example.selection.AlphaTest", "example.selection.BetaTest"), union);
        var verified = runner("verifyTestShards", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.count=2").build();
        assertNull(verified.task(":test"));
        assertNull(verified.task(":prepareTestSelection"));
        assertNull(verified.task(":acquireTestGitChanges"));
        assertEquals(identity, Files.readString(frozen.resolve("selection.id")));
        Files.writeString(frozen.resolve("decision/decision.id"), "changed");
        var failed = runner("test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.count=2",
                "-Pshardpilot.selectionSnapshotDirectory=" + frozen).buildAndFail();
        assertNull(failed.task(":test"));
        assertFalse(Files.exists(output("affected-testdata", "selection/selected.yaml")));
    }

    @Test
    void multiNodeExecutionRequiresFrozenInputAndUnknownProfilesNeverFallBack() throws Exception {
        fixture();
        var missing = runner("test", "-Pshardpilot.count=2").buildAndFail();
        assertTrue(missing.getOutput().contains("requires selectionSnapshotDirectory"));
        assertNull(missing.task(":test"));
        var unknown = runner("test", "-Pshardpilot.selectionProfile=unknown-testdata").buildAndFail();
        assertNull(unknown.task(":test"));
        assertFalse(Files.exists(output("unknown-testdata", "selection/selected.yaml")));
    }

    @Test
    void kotlinDslExposesTheSameProfileAndNativeFailuresRemainFatal() throws Exception {
        copy(Path.of("examples/gradle/junit-jupiter/selection-profiles-kotlin"), directory);
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"kotlin-profiles-testdata\"\n");
        runner("test").build();
        assertEquals(Set.of("example.selection.AlphaTest", "example.selection.BetaTest"), actual("smoke-testdata", 1));
        Path test = directory.resolve("src/test/java/example/selection/BetaTest.java");
        Files.writeString(test, "package example.selection; class BetaTest { @org.junit.jupiter.api.Test void nativeFailure() { throw new AssertionError(); } }");
        var failed = runner("test").buildAndFail();
        assertEquals(TaskOutcome.FAILED, requireNonNull(failed.task(":test")).getOutcome());
    }

    @Test
    void directGitTaskRejectsUnsafeSuiteBeforeWritingAnySnapshot() throws Exception {
        fixture();
        var failed = runner("acquireTestGitChanges", "-Pshardpilot.selectionSuite=../escape-testdata").buildAndFail();
        assertTrue(failed.getOutput().contains("Selection suite"));
        assertFalse(Files.exists(directory.resolve("build/shardpilot/test/escape-testdata/git/changes.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dirty", "different-head"})
    void frozenCheckoutValidationRunsBeforeReusingAlreadySuccessfulNativeOutputs(String mutation) throws Exception {
        fixture();
        String[] ids = gitFixture();
        runner("prepareTestShardManifests", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.gitBase=main-testdata",
                "-Pshardpilot.count=2").build();
        Path frozen = directory.resolve("build/downloaded-selection");
        copy(output("affected-testdata", "selection"), frozen);
        String[] arguments = {"test", "-Pshardpilot.selectionProfile=affected-testdata", "-Pshardpilot.count=2",
                "-Pshardpilot.selectionSnapshotDirectory=" + frozen};
        runner(arguments).build();
        String previous = Files.readString(output("affected-testdata", "actual/actual-1.yaml"));
        String identity = Files.readString(frozen.resolve("selection.id"));
        // Neither change touches Java sources: native compile/Test could otherwise be UP-TO-DATE.
        if (mutation.equals("dirty")) Files.writeString(directory.resolve("src/modified.txt"), "dirty checkout\n");
        else Files.writeString(directory.resolve(".git/refs/heads/feature-testdata"), ids[0] + "\n");
        var failed = runner(arguments).buildAndFail();
        assertTrue(failed.getOutput().contains("Reusing configuration cache."));
        assertEquals(TaskOutcome.FAILED, requireNonNull(failed.task(":prepareTestSelection")).getOutcome());
        assertNull(failed.task(":test"));
        assertNull(failed.task(":acquireTestGitChanges"));
        assertTrue(failed.getOutput().contains(mutation.equals("dirty") ? "requires a clean checkout" : "another checkout"));
        assertEquals(previous, Files.readString(output("affected-testdata", "actual/actual-1.yaml")));
        assertEquals(identity, Files.readString(frozen.resolve("selection.id")));
        assertFalse(Files.exists(output("affected-testdata", "selection/selection.id")));
    }

    private Set<String> executedAffectedClasses(int index) throws Exception {
        Path reports = output("affected-testdata", "test-results/" + index);
        var result = new java.util.TreeSet<String>();
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("TEST-")).toList()) {
                var root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                assertEquals("0", root.getAttribute("failures"));
                assertEquals("0", root.getAttribute("errors"));
                assertEquals("0", root.getAttribute("skipped"));
                var cases = root.getElementsByTagName("testcase");
                for (int item = 0; item < cases.getLength(); item++) {
                    assertTrue(result.add(cases.item(item).getAttributes().getNamedItem("classname").getNodeValue()));
                }
            }
        }
        return result;
    }

    private Set<String> actual(String profile, int index) throws Exception {
        return ClassManifest.read(output(profile, "actual/actual-" + index + ".yaml"), false);
    }

    private Path output(String profile, String path) {
        return directory.resolve("build/shardpilot/test/selections/profile-" + profile + "/" + path);
    }

    private void fixture() throws Exception {
        copy(Path.of("examples/gradle/junit-jupiter/selection-profiles"), directory);
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'profiles-testdata'\n");
        VerifiedTestKit.create(directory); // Checksum policy belongs in the synthetic committed baseline.
    }

    private String[] gitFixture() throws Exception {
        Path launcher = Path.of("shardpilot-core/src/test/resources/git-fixture/GitFixture.java").toAbsolutePath();
        Files.writeString(directory.resolve(".shardpilot-fixture-testdata"), "synthetic Git fixture\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), launcher.toString(), directory.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output.split("\\R");
    }

    private static void copy(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
    }

    private GradleRunner runner(String... args) {
        var arguments = new ArrayList<>(List.of(args));
        arguments.addAll(List.of("--stacktrace", "--console=plain", "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        return VerifiedTestKit.create(directory).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(arguments);
    }
}
