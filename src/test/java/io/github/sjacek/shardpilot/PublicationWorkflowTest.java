package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Structural YAML checks guard publication dependencies and credential isolation without Portal access. */
class PublicationWorkflowTest {
    @Test
    void releaseTagsRunTheSharedMatrixBeforePublicationWithoutBypassConditions() throws IOException {
        Map<?, ?> release = workflow("publish");
        Map<?, ?> events = mapping(release.get("on"));
        assertEquals(Set.of("push"), events.keySet());
        assertEquals(List.of("v[0-9]*"), mapping(events.get("push")).get("tags"));
        Map<?, ?> concurrency = mapping(release.get("concurrency"));
        assertEquals(false, concurrency.get("cancel-in-progress"));
        assertTrue(concurrency.get("group").toString().startsWith("publish-"));

        Map<?, ?> jobs = mapping(release.get("jobs"));
        Map<?, ?> validation = mapping(jobs.get("validate-tag"));
        assertEquals("github.event.created == true && github.event.deleted == false", validation.get("if"));
        Map<?, ?> guard = stepNamed(validation, "Validate the stable version tag");
        assertEquals("${{ github.ref_name }}", mapping(guard.get("env")).get("RELEASE_TAG"));
        assertTrue(guard.get("run").toString().contains("verifyReleaseTag -PreleaseTag=\"$RELEASE_TAG\""));

        Map<?, ?> matrix = mapping(jobs.get("compatibility"));
        assertEquals("validate-tag", matrix.get("needs"));
        assertEquals("./.github/workflows/compatibility.yml", matrix.get("uses"));
        assertNull(matrix.get("secrets"), "release credentials must not be inherited by matrix jobs");
        assertNull(matrix.get("if"));

        Map<?, ?> publication = mapping(jobs.get("publish"));
        assertEquals(List.of("validate-tag", "compatibility"), publication.get("needs"));
        assertNull(publication.get("if"), "default success gating must not be replaced by always()");
        for (Object job : jobs.values()) {
            assertNull(mapping(job).get("continue-on-error"), "every release stage must be mandatory");
        }
    }

    @Test
    void everyMatrixCellIsMandatoryAndTestsTheSameCommitAsPublication() throws IOException {
        Map<?, ?> compatibility = workflow("compatibility");
        Map<?, ?> events = mapping(compatibility.get("on"));
        assertTrue(events.containsKey("workflow_call"));
        assertEquals(List.of("**"), mapping(events.get("push")).get("branches"));
        assertFalse(mapping(events.get("push")).containsKey("tags"), "tag builds must not run a duplicate matrix");
        assertTrue(mapping(compatibility.get("concurrency")).get("group").toString().startsWith("compatibility-"));

        Map<?, ?> matrixJob = mapping(mapping(compatibility.get("jobs")).get("compatibility"));
        Map<?, ?> strategy = mapping(matrixJob.get("strategy"));
        assertEquals(false, strategy.get("fail-fast"));
        assertNull(matrixJob.get("continue-on-error"));
        Map<?, ?> matrix = mapping(strategy.get("matrix"));
        Set<String> cells = new HashSet<>();
        for (Object gradle : sequence(matrix.get("gradle"))) {
            for (Object java : sequence(matrix.get("java"))) cells.add(gradle + "/" + java);
        }
        for (Object exclusion : sequence(matrix.get("exclude"))) {
            Map<?, ?> pair = mapping(exclusion);
            assertTrue(cells.remove(pair.get("gradle") + "/" + pair.get("java")));
        }
        assertEquals(Set.of("9.0.0/17", "9.0.0/21", "9.1.0/17", "9.1.0/21", "9.1.0/25",
                "9.8.1/17", "9.8.1/21", "9.8.1/25"), cells);
        String checks = stepNamed(matrixJob, "Verify the plugin against the consumer runtime").get("run").toString();
        assertTrue(checks.contains("check assemble"));
        assertTrue(checks.contains("-PtestGradleVersion=${{ matrix.gradle }}"));
        assertTrue(checks.contains("-PtestJavaVersion=${{ matrix.java }}"));
        assertTestedCheckout(matrixJob);
        Map<?, ?> releaseJobs = mapping(workflow("publish").get("jobs"));
        assertTestedCheckout(mapping(releaseJobs.get("validate-tag")));
        assertTestedCheckout(mapping(releaseJobs.get("publish")));
    }

    @Test
    void credentialsExistOnlyInThePublishingStepAndAreNotCached() throws IOException {
        Map<?, ?> release = workflow("publish");
        assertEquals(Map.of("contents", "read"), release.get("permissions"));
        assertNull(release.get("env"));
        Map<?, ?> jobs = mapping(release.get("jobs"));
        Map<?, ?> publication = mapping(jobs.get("publish"));
        assertEquals("gradle-plugin-portal", publication.get("environment"));
        assertNull(publication.get("env"));
        Map<?, ?> setup = stepUsing(publication, "gradle/actions/setup-gradle@");
        assertEquals(true, mapping(setup.get("with")).get("cache-disabled"));
        Map<?, ?> publishingStep = stepNamed(publication, "Validate and publish the plugin");
        assertEquals(Map.of("GRADLE_PUBLISH_KEY", "${{ secrets.GRADLE_PUBLISH_KEY }}",
                "GRADLE_PUBLISH_SECRET", "${{ secrets.GRADLE_PUBLISH_SECRET }}"), publishingStep.get("env"));
        String commands = publishingStep.get("run").toString();
        assertTrue(commands.contains("set -euo pipefail"));
        assertTrue(commands.contains("${GRADLE_PUBLISH_KEY:-}"));
        assertTrue(commands.contains("${GRADLE_PUBLISH_SECRET:-}"));
        assertTrue(commands.contains("publishPlugins --validate-only"));
        assertTrue(commands.indexOf("publishPlugins --validate-only") < commands.indexOf("publishPlugins --no-configuration-cache"));
        List<String> publications = commands.lines().filter(line -> line.contains("./gradlew publishPlugins")).toList();
        assertEquals(2, publications.size());
        for (String command : publications) {
            assertTrue(command.contains("--no-configuration-cache"));
            assertTrue(command.contains("--no-daemon"));
        }
        assertNull(mapping(stepUsing(publication, "actions/setup-java@").get("with")).get("cache"));
        for (Object step : sequence(publication.get("steps"))) {
            if (step != publishingStep) assertNull(mapping(step).get("env"));
        }
        assertFalse(Files.readString(Path.of(".github/workflows/compatibility.yml")).contains("secrets."));
        assertFalse(mapping(jobs.get("validate-tag")).toString().contains("secrets."));
    }

    private static Map<?, ?> workflow(String name) throws IOException {
        String yaml = Files.readString(Path.of(".github/workflows/" + name + ".yml"));
        return mapping(new Load(LoadSettings.builder().build()).loadFromString(yaml));
    }

    private static Map<?, ?> mapping(Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static List<?> sequence(Object value) {
        return assertInstanceOf(List.class, value);
    }

    private static Map<?, ?> stepNamed(Map<?, ?> job, String name) {
        return sequence(job.get("steps")).stream().map(PublicationWorkflowTest::mapping)
                .filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
    }

    private static Map<?, ?> stepUsing(Map<?, ?> job, String action) {
        return sequence(job.get("steps")).stream().map(PublicationWorkflowTest::mapping)
                .filter(step -> step.get("uses") instanceof String uses && uses.startsWith(action))
                .findFirst().orElseThrow();
    }

    private static void assertTestedCheckout(Map<?, ?> job) {
        Map<?, ?> options = mapping(stepUsing(job, "actions/checkout@").get("with"));
        assertEquals("${{ github.sha }}", options.get("ref"));
        assertEquals(false, options.get("persist-credentials"));
    }
}
