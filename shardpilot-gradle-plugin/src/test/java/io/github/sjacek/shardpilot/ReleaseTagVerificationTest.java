package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual release guard script with the publishing build's Wrapper, without publishing. */
class ReleaseTagVerificationTest {
    @TempDir
    Path directory;

    @BeforeEach
    void fixture() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'release-guard-fixture'\n");
        Files.writeString(directory.resolve("build.gradle"), "version = '0.1.0'\napply from: 'release-verification.gradle'\n");
        Files.copy(Path.of("gradle/release-verification.gradle"), directory.resolve("release-verification.gradle"));
        Files.writeString(directory.resolve("gradle.properties"), """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                org.gradle.jvmargs=-Xmx256m -Dfile.encoding=UTF-8
                org.gradle.workers.max=1
                """);
    }

    @Test
    void acceptsMatchingStableTagAndReusesConfigurationCache() {
        BuildResult result = runner("-PreleaseTag=v0.1.0").build();
        assertEquals(SUCCESS, requireNonNull(result.task(":verifyReleaseTag")).getOutcome());
        BuildResult reused = runner("-PreleaseTag=v0.1.0").build();
        assertTrue(reused.getOutput().contains("Reusing configuration cache."));
        assertTrue(reused.getOutput().contains("Release tag matches the stable project version."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.1.0", "v01.1.0", "v0.01.0", "v0.1.00", "v0.1.0-SNAPSHOT", "v0.1.0-rc.1", "v0.1.0+build.1"})
    void rejectsMissingPrefixMalformedVersionsAndNonFinalTags(String tag) {
        assertTrue(runner("-PreleaseTag=" + tag).buildAndFail().getOutput()
                .contains("releaseTag must be a stable vMAJOR.MINOR.PATCH tag"));
    }

    @Test
    void rejectsMissingTagProperty() {
        assertTrue(runner().buildAndFail().getOutput().contains("releaseTag must be a stable vMAJOR.MINOR.PATCH tag"));
    }

    @Test
    void rejectsMismatchedTagAfterSuccessfulCachedVerification() {
        runner("-PreleaseTag=v0.1.0").build();
        assertTrue(runner("-PreleaseTag=v0.2.0").buildAndFail().getOutput()
                .contains("releaseTag must equal v followed by project.version"));
    }

    @Test
    void readsTheEvaluatedProjectVersionInsteadOfDerivingItFromTheTag() throws IOException {
        Files.writeString(directory.resolve("build.gradle"), """
                version = '0.' + '2.0'
                apply from: 'release-verification.gradle'
                """);
        assertTrue(runner("-PreleaseTag=v0.2.0").build().getOutput()
                .contains("Release tag matches the stable project version."));
        assertTrue(runner("-PreleaseTag=v0.1.0").buildAndFail().getOutput()
                .contains("releaseTag must equal v followed by project.version"));
    }

    private GradleRunner runner(String... arguments) {
        var options = new ArrayList<>(List.of("verifyReleaseTag", "--stacktrace", "--console=plain",
                "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        options.addAll(List.of(arguments));
        // Release validation belongs to the fixed Wrapper build, not the consumer compatibility target.
        return GradleRunner.create().withProjectDir(directory.toFile()).withArguments(options);
    }
}
