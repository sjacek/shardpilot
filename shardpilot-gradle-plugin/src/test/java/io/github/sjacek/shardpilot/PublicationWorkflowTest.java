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

/**
 * Structural YAML checks guard publication dependencies and credential isolation without Portal access.
 * Python unit and loopback HTTP tests cover recovery behavior; these checks ensure CI actually runs
 * them and retains only the checkpoint needed to resume an already accepted Central deployment.
 */
class PublicationWorkflowTest {
    @Test
    void bothReleaseTriggersRequireTheSharedMatrixAndSelectExactlyOnePublisher() throws IOException {
        Map<?, ?> release = workflow("publish");
        Map<?, ?> events = mapping(release.get("on"));
        assertEquals(Set.of("push", "workflow_dispatch"), events.keySet());
        assertEquals(List.of("v[0-9]*"), mapping(events.get("push")).get("tags"));
        Map<?, ?> concurrency = mapping(release.get("concurrency"));
        assertEquals(false, concurrency.get("cancel-in-progress"));
        assertTrue(concurrency.get("group").toString().startsWith("publish-"));

        Map<?, ?> jobs = mapping(release.get("jobs"));
        Map<?, ?> validation = mapping(jobs.get("validate-tag"));
        assertEquals("github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.event.created == true && github.event.deleted == false)", validation.get("if"));
        Map<?, ?> guard = stepNamed(validation, "Validate the stable version tag");
        assertEquals("${{ github.ref_name }}", mapping(guard.get("env")).get("RELEASE_TAG"));
        assertTrue(guard.get("run").toString().contains("verifyReleaseTag -PreleaseTag=\"$RELEASE_TAG\""));

        Map<?, ?> matrix = mapping(jobs.get("compatibility"));
        assertEquals("validate-tag", matrix.get("needs"));
        assertEquals("./.github/workflows/compatibility.yml", matrix.get("uses"));
        assertNull(matrix.get("secrets"), "release credentials must not be inherited by matrix jobs");
        assertNull(matrix.get("if"));

        for (String publisher : List.of("publish", "publish-maven")) {
            Map<?, ?> publication = mapping(jobs.get(publisher));
            assertEquals(List.of("validate-tag", "compatibility"), publication.get("needs"));
            String trigger = publisher.equals("publish") ? "push" : "workflow_dispatch";
            assertEquals("github.event_name == '" + trigger + "'", publication.get("if"),
                    "select one destination while retaining default prerequisite success gating");
            assertTestedCheckout(publication);
        }
        for (Object job : jobs.values()) {
            assertNull(mapping(job).get("continue-on-error"), "every release stage must be mandatory");
        }
    }

    @Test
    void manualMavenPublicationRequiresAnExistingTagRefAndUsesItsImmutableCheckoutSha() throws IOException {
        var release = workflow("publish");
        var events = mapping(release.get("on"));
        var inputs = mapping(mapping(events.get("workflow_dispatch")).get("inputs"));
        assertEquals(Set.of("maven_deployment_id"), inputs.keySet(), "only a resume ID is accepted; never a separate tag input");
        assertEquals(false, mapping(inputs.get("maven_deployment_id")).get("required"));
        assertEquals("string", mapping(inputs.get("maven_deployment_id")).get("type"));
        var jobs = mapping(release.get("jobs"));
        var validation = mapping(jobs.get("validate-tag"));
        var refGuard = stepNamed(validation, "Require a tag reference");
        assertEquals(Map.of("RELEASE_REF_TYPE", "${{ github.ref_type }}"), refGuard.get("env"));
        String commands = refGuard.get("run").toString();
        assertEquals("bash", refGuard.get("shell"));
        assertTrue(commands.contains("set -euo pipefail"));
        assertTrue(commands.contains("[[ \"$RELEASE_REF_TYPE\" != 'tag' ]]"));
        assertTrue(commands.contains("exit 1"));
        assertNull(refGuard.get("if"), "branches must be rejected on both release paths");
        assertTrue(sequence(validation.get("steps")).indexOf(refGuard)
                < sequence(validation.get("steps")).indexOf(stepNamed(validation, "Check out the tagged commit")));
        for (String job : List.of("validate-tag", "publish", "publish-maven")) {
            assertTestedCheckout(mapping(jobs.get(job)));
        }
        assertTrue(release.toString().contains("inputs.maven_deployment_id"));
        assertFalse(release.toString().contains("inputs.tag"));
        assertTrue(release.get("run-name").toString().contains("github.ref_name"));
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

    @Test
    void sharedReleaseGateAlsoRequiresEveryMavenRuntimeAndRealInvokerBuilds() throws IOException {
        var maven = mapping(mapping(workflow("compatibility").get("jobs")).get("maven"));
        assertEquals(List.of("17", "21", "25"), mapping(mapping(maven.get("strategy")).get("matrix")).get("java"));
        assertEquals(false, mapping(maven.get("strategy")).get("fail-fast"));
        assertNull(maven.get("if"));
        assertNull(maven.get("continue-on-error"));
        assertTestedCheckout(maven);
        assertEquals("bash ./mvnw -B -ntp verify", stepNamed(maven, "Verify Maven core, adapter and real runner builds").get("run"));
        String wrapper = Files.readString(Path.of(".mvn/wrapper/maven-wrapper.properties"));
        assertTrue(wrapper.contains("apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"));
        assertTrue(wrapper.contains("distributionSha256Sum="));
        assertFalse(maven.toString().contains("secrets."));
    }

    @Test
    void nativeMavenReactorVersionsMatchTheEvaluatedGradleReleaseVersion() throws Exception {
        String version = System.getProperty("shardpilot.test.releaseVersion");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertEquals(version, xpath.evaluate("/project/version", factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile())));
        for (String module : List.of("shardpilot-core", "shardpilot-maven-plugin")) {
            assertEquals(version, xpath.evaluate("/project/parent/version",
                    factory.newDocumentBuilder().parse(Path.of(module, "pom.xml").toFile())));
        }
    }

    @Test
    void centralPublicationUsesItsOwnProtectedSecretsAndExplicitlyEnablesUpload() throws IOException {
        var central = mapping(mapping(workflow("publish").get("jobs")).get("publish-maven"));
        assertEquals("maven-central", central.get("environment"));
        assertNull(central.get("env"));
        var java = mapping(stepUsing(central, "actions/setup-java@").get("with"));
        assertNull(java.get("cache"), "credentialed builds must not restore shared Maven caches");
        assertEquals("central", java.get("server-id"));
        assertEquals("MAVEN_CENTRAL_USERNAME", java.get("server-username-env-var"));
        assertEquals("MAVEN_CENTRAL_PASSWORD", java.get("server-password-env-var"));
        assertEquals("${{ runner.temp }}/shardpilot-maven", java.get("settings-path"));
        var publishing = stepNamed(central, "Publish the signed Maven release");
        assertEquals(Map.of("MAVEN_CENTRAL_USERNAME", "${{ secrets.MAVEN_CENTRAL_USERNAME }}",
                "MAVEN_CENTRAL_PASSWORD", "${{ secrets.MAVEN_CENTRAL_PASSWORD }}",
                "MAVEN_GPG_KEY", "${{ secrets.MAVEN_GPG_KEY }}",
                "MAVEN_GPG_PASSPHRASE", "${{ secrets.MAVEN_GPG_PASSPHRASE }}",
                "MAVEN_DEPLOYMENT_ID", "${{ inputs.maven_deployment_id }}"), publishing.get("env"));
        String command = publishing.get("run").toString();
        assertTrue(command.contains("python3 -B scripts/central_publication.py"));
        assertTrue(command.contains("--settings \"$RUNNER_TEMP/shardpilot-maven/settings.xml\""));
        String publisher = Files.readString(Path.of("scripts/central_publication.py"));
        assertTrue(publisher.contains("'-Dcentral.skipUpload=false'"));
        assertTrue(publisher.contains("'-Dcentral.waitUntil=uploaded'"));
        assertTrue(publisher.contains("MAVEN_GPG_PASSPHRASE"));
        var evidence = stepNamed(central, "Retain Central deployment identity and status");
        assertEquals("always()", evidence.get("if"));
        var retained = mapping(evidence.get("with"));
        assertEquals("build/central-publication/state.json", retained.get("path"));
        assertEquals("central-publication-${{ github.ref_name }}", retained.get("name"));
        assertEquals(30, retained.get("retention-days"));
        assertEquals("warn", retained.get("if-no-files-found"), "a failed preflight may not create a checkpoint");
        var python = mapping(stepUsing(central, "actions/setup-python@").get("with"));
        assertEquals("3.12", python.get("python-version"));
        assertNull(python.get("cache"));
        assertFalse(command.contains("gpg.skip"));
        assertFalse(command.contains("set -x"));
        for (Object step : sequence(central.get("steps"))) {
            if (step != publishing) assertNull(mapping(step).get("env"));
        }
        assertFalse(central.toString().contains("GRADLE_PUBLISH_SECRET"));
    }

    @Test
    void signedReleaseBundleIsARequiredSecretFreeCompatibilityGate() throws IOException {
        var gate = mapping(mapping(workflow("compatibility").get("jobs")).get("release-artifacts"));
        assertNull(gate.get("if"));
        assertNull(gate.get("continue-on-error"));
        assertTestedCheckout(gate);
        assertFalse(gate.toString().contains("secrets."));
        assertEquals("bash scripts/verify-maven-release.sh", stepNamed(gate, "Verify the signed release with a disposable key").get("run"));
        var recovery = stepNamed(gate, "Verify resilient Central status polling without publication credentials");
        // The wildcard includes the loopback integration suite, not just the unit-test module.
        assertEquals("python3 -B -m unittest discover -s scripts -p 'test_central_publication*.py' -v", recovery.get("run"));
        assertNull(recovery.get("if"), "recovery regressions must be a required part of the release gate");
        assertNull(recovery.get("continue-on-error"));
        assertNull(recovery.get("env"), "fixtures use dummy credentials rather than publisher secrets");
        assertEquals("3.12", mapping(stepUsing(gate, "actions/setup-python@").get("with")).get("python-version"));
        String verification = Files.readString(Path.of("scripts/verify-maven-release.sh"));
        assertTrue(verification.contains("-Dcentral.skipUpload=true"));
        assertTrue(verification.contains("--quick-generate-key"));
        assertTrue(verification.contains("gpg --no-options --batch --verify"));
        assertTrue(verification.contains("VerifyReleaseArtifacts.java"));
        assertFalse(verification.contains("-Dcentral.skipUpload=false"));
        var artifact = mapping(stepUsing(gate, "actions/upload-artifact@").get("with"));
        assertEquals("build/release-verification/report.json", artifact.get("path"), "never retain a test private key or publishable bundle");
    }

    @Test
    void centralReleaseProfileDefaultsToLocalBundlesAndAttachesSourcesDocsAndSignatures() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        String profile = "/project/profiles/profile[id='central-release']";
        assertEquals("true", xpath.evaluate(profile + "/properties/central.skipUpload", pom));
        assertEquals("", xpath.evaluate(profile + "/activation/activeByDefault", pom));
        String plugins = profile + "/build/plugins/plugin";
        String central = plugins + "[artifactId='central-publishing-maven-plugin']";
        assertEquals("0.12.0", xpath.evaluate(central + "/version", pom));
        assertEquals("true", xpath.evaluate(central + "/extensions", pom));
        assertEquals("${central.skipUpload}", xpath.evaluate(central + "/configuration/skipUpload", pom));
        assertEquals("true", xpath.evaluate(central + "/configuration/autoPublish", pom));
        assertEquals("${central.waitUntil}", xpath.evaluate(central + "/configuration/waitUntil", pom));
        assertEquals("published", xpath.evaluate(profile + "/properties/central.waitUntil", pom));
        assertEquals("ShardPilot ${project.version}", xpath.evaluate(central + "/configuration/deploymentName", pom));
        assertEquals("jar-no-fork", xpath.evaluate(plugins + "[artifactId='maven-source-plugin']/executions/execution/goals/goal", pom));
        assertEquals("jar", xpath.evaluate(plugins + "[artifactId='maven-javadoc-plugin']/executions/execution/goals/goal", pom));
        String gpg = plugins + "[artifactId='maven-gpg-plugin']";
        assertEquals("bc", xpath.evaluate(gpg + "/configuration/signer", pom));
        assertEquals("true", xpath.evaluate(gpg + "/configuration/bestPractices", pom));
        assertEquals("verify", xpath.evaluate(gpg + "/executions/execution/phase", pom));
        assertEquals("sign", xpath.evaluate(gpg + "/executions/execution/goals/goal", pom));
        assertEquals("", xpath.evaluate(gpg + "/configuration/passphrase", pom));
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
