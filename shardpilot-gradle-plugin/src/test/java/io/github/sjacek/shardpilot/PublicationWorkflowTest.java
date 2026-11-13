package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural YAML checks guard publication dependencies and credential isolation without Portal access.
 * Python unit and loopback HTTP tests cover recovery behavior; these checks ensure CI actually runs
 * them and retains only the checkpoint needed to resume an already accepted Central deployment.
 */
class PublicationWorkflowTest {
    @Test
    void mavenVerificationStartsBeforeWarmupTestsScansAndCredentialedPublication() throws IOException {
        var compatibility = mapping(workflow("compatibility").get("jobs"));
        for (var pair : Map.of("warm-cache", "Warm Maven reactor and native fixture dependencies without running tests",
                "maven", "Verify Maven core, adapter and real runner builds",
                "release-artifacts", "Verify the signed release with a disposable key").entrySet()) {
            var job = mapping(compatibility.get(pair.getKey()));
            assertMavenVerificationBefore(job, pair.getValue());
        }
        var scan = mapping(mapping(workflow("security").get("jobs")).get("scan"));
        assertMavenVerificationBefore(scan, "Build Maven candidates and resolve runtime inventory");
        assertEquals("matrix.distribution == 'maven'", stepNamed(scan, "Enable Maven dependency verification").get("if"));
        var publish = mapping(mapping(workflow("publish").get("jobs")).get("publish-maven"));
        assertMavenVerificationBefore(publish, "Publish the signed Maven release");
        assertEquals("inputs.maven_deployment_id == ''", stepNamed(publish, "Enable Maven dependency verification").get("if"),
                "status-only recovery must not download or load Maven tooling");
        assertFalse(stepNamed(publish, "Enable Maven dependency verification").toString().contains("secrets."));
    }

    private static void assertMavenVerificationBefore(Map<?, ?> job, String consumer) {
        var bootstrap = stepNamed(job, "Enable Maven dependency verification");
        assertEquals("python3 -B scripts/maven_verification.py prepare", bootstrap.get("run"));
        assertNull(bootstrap.get("continue-on-error"));
        assertTrue(sequence(job.get("steps")).indexOf(bootstrap) < sequence(job.get("steps")).indexOf(stepNamed(job, consumer)));
    }

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
            assertEquals(List.of("validate-tag", "compatibility", "published-artifact-audit"), publication.get("needs"));
            String trigger = publisher.equals("publish") ? "push" : "workflow_dispatch";
            assertEquals("github.event_name == '" + trigger + "'", publication.get("if"),
                    "select one destination while retaining default prerequisite success gating");
            assertTestedCheckout(publication);
        }
        for (Object job : jobs.values()) {
            assertNull(mapping(job).get("continue-on-error"), "every release stage must be mandatory");
        }
    }

    /** The published baseline gate is separate from candidate testing and never receives publisher secrets. */
    @Test
    void fullPublishedAuditIsMandatoryBeforeBothPublishersAndSupportsManualRuns() throws IOException {
        var publication = mapping(workflow("publish").get("jobs"));
        var gate = mapping(publication.get("published-artifact-audit"));
        assertEquals("validate-tag", gate.get("needs"));
        assertEquals("./.github/workflows/published-artifact-audit.yml", gate.get("uses"));
        assertNull(gate.get("if"));
        assertNull(gate.get("secrets"));
        assertNull(gate.get("with"), "release gates use the reviewed published baseline, never the unpublished candidate tag");
        for (String publisher : List.of("publish", "publish-maven")) {
            assertTrue(sequence(mapping(publication.get(publisher)).get("needs")).contains("published-artifact-audit"));
        }
        var audit = workflow("published-artifact-audit");
        assertEquals(Set.of("workflow_dispatch", "workflow_call"), mapping(audit.get("on")).keySet());
        assertEquals(Map.of("contents", "read"), audit.get("permissions"));
        for (Object event : mapping(audit.get("on")).values()) {
            var version = mapping(mapping(mapping(event).get("inputs")).get("version"));
            assertEquals("string", version.get("type"));
            assertEquals("", version.get("default"));
            assertEquals(false, version.get("required"));
            assertNull(mapping(event).get("secrets"));
        }
        var job = mapping(mapping(audit.get("jobs")).get("audit"));
        assertEquals("ubuntu-latest", job.get("runs-on"));
        assertNull(job.get("environment"));
        assertNull(job.get("continue-on-error"));
        var checkout = mapping(stepUsing(job, "actions/checkout@").get("with"));
        assertEquals("${{ github.sha }}", checkout.get("ref"));
        assertEquals(false, checkout.get("persist-credentials"));
        assertEquals(0, checkout.get("fetch-depth"));
        var execution = stepNamed(job, "Audit published artifacts and exercise evidence rejection");
        assertEquals("audit", execution.get("id"));
        assertNull(execution.get("if"));
        assertNull(execution.get("continue-on-error"));
        assertEquals(Map.of("AUDIT_VERSION", "${{ inputs.version }}"), execution.get("env"));
        String commands = execution.get("run").toString();
        assertTrue(commands.contains("set -euo pipefail"), "tee must not hide a failed audit");
        assertTrue(commands.contains("scripts/run_published_artifact_audit.py --version \"$AUDIT_VERSION\""));
        assertTrue(commands.contains("testing/published-artifacts/check_evidence.py"));
        assertFalse(commands.contains("${{"), "untrusted input enters through a quoted environment variable");
        assertFalse(commands.contains("--reuse-downloads"));
        assertFalse(commands.contains("--skip-build"));
        assertFalse(audit.toString().contains("secrets."));
        assertFalse(audit.toString().contains("actions/cache"));
    }

    @Test
    void publishedAuditRetainsFailureDiagnosticsWithoutUploadingDependencyHomes() throws IOException {
        var audit = mapping(mapping(workflow("published-artifact-audit").get("jobs")).get("audit"));
        var summary = stepNamed(audit, "Summarize and package audit evidence");
        var upload = stepNamed(audit, "Retain audit reports and raw network traces");
        assertEquals("always()", summary.get("if"));
        assertEquals("always()", upload.get("if"));
        assertTrue(mapping(summary.get("env")).get("AUDIT_OUTCOME").toString().contains("steps.audit.outcome"));
        assertEquals("python3 -B scripts/published_audit_report.py --outcome \"$AUDIT_OUTCOME\"", summary.get("run"));
        var artifact = mapping(upload.get("with"));
        assertEquals(30, artifact.get("retention-days"));
        assertEquals("error", artifact.get("if-no-files-found"));
        String paths = artifact.get("path").toString();
        assertTrue(paths.contains("build/published-artifact-audit-report.zip"));
        assertTrue(paths.contains("build/published-artifact-audit/ci-report.json"));
        assertFalse(paths.contains("**"));
        assertFalse(paths.contains("seed"));
        assertFalse(paths.contains("tool-image"));
        var policy = mapping(new Load(LoadSettings.builder().build()).loadFromString(
                Files.readString(Path.of("security/published-artifact-audit.json"))));
        assertEquals(1, policy.get("schemaVersion"));
        assertTrue(policy.get("defaultPublishedVersion").toString().matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        assertTrue(policy.get("defaultSourceCommit").toString().matches("[a-f0-9]{40}"));
        assertTrue(policy.get("publisherPrimaryFingerprint").toString().matches("[A-F0-9]{40}"));
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
        // Required status contexts use these exact labels; matrix upgrades must update the live ruleset too.
        assertEquals("Gradle ${{ matrix.gradle }} / JDK ${{ matrix.java }}", matrixJob.get("name"));
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
                "9.9.1/17", "9.9.1/21", "9.9.1/25"), cells);
        String checks = stepNamed(matrixJob, "Verify the plugin against the consumer runtime").get("run").toString();
        assertTrue(checks.contains("check assemble"));
        assertTrue(checks.contains("-PtestGradleVersion=${{ matrix.gradle }}"));
        assertTrue(checks.contains("-PtestJavaVersion=${{ matrix.java }}"));
        assertTestedCheckout(matrixJob);
        Map<?, ?> releaseJobs = mapping(workflow("publish").get("jobs"));
        assertTestedCheckout(mapping(releaseJobs.get("validate-tag")));
        assertTestedCheckout(mapping(releaseJobs.get("publish")));
    }

    /** The build Wrapper and newest consumer cell move together; generated bytes have an independent upstream pin. */
    @Test
    void gradleWrapperAndNewestConsumerTargetUseTheVerifiedDistribution() throws Exception {
        Properties wrapper = new Properties();
        wrapper.load(new StringReader(Files.readString(Path.of("gradle/wrapper/gradle-wrapper.properties"))));
        String checksum = "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228";
        assertEquals("https://services.gradle.org/distributions/gradle-9.9.1-bin.zip",
                wrapper.getProperty("distributionUrl"));
        assertEquals(checksum, wrapper.getProperty("distributionSha256Sum"));
        assertEquals("true", wrapper.getProperty("validateDistributionUrl"));
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains("gradleVersion = '9.9.1'"));
        assertTrue(build.contains("distributionSha256Sum = '" + checksum + "'"));
        var matrix = mapping(mapping(mapping(workflow("compatibility").get("jobs"))
                .get("compatibility")).get("strategy"));
        assertEquals(List.of("9.0.0", "9.1.0", "9.9.1"), mapping(matrix.get("matrix")).get("gradle"));
        byte[] jar = Files.readAllBytes(Path.of("gradle/wrapper/gradle-wrapper.jar"));
        assertEquals("621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)));
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
        assertEquals("Maven 3.10.0 / JDK ${{ matrix.java }}", maven.get("name"));
        assertEquals("Signed Maven release bundle (no upload)",
                mapping(mapping(workflow("compatibility").get("jobs")).get("release-artifacts")).get("name"));
        assertEquals(List.of("17", "21", "25"), mapping(mapping(maven.get("strategy")).get("matrix")).get("java"));
        assertEquals(false, mapping(maven.get("strategy")).get("fail-fast"));
        assertEquals("${{ !cancelled() }}", maven.get("if"));
        assertNull(maven.get("continue-on-error"));
        assertTestedCheckout(maven);
        assertEquals("bash ./mvnw -B -ntp -Dmaven.repo.local=\"$GITHUB_WORKSPACE/build/ci-dependencies/maven\" verify",
                stepNamed(maven, "Verify Maven core, adapter and real runner builds").get("run"));
        // CI invokes the Wrapper, so the status label must describe its actual pinned runtime.
        Properties wrapper = new Properties();
        wrapper.load(new StringReader(Files.readString(Path.of(".mvn/wrapper/maven-wrapper.properties"))));
        assertEquals("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip",
                wrapper.getProperty("distributionUrl"));
        String checksum = wrapper.getProperty("distributionSha256Sum");
        assertNotNull(checksum, "the Maven distribution must be checksum-verified");
        assertTrue(checksum.matches("[a-f0-9]{64}"), "a nonempty SHA-256 checksum is required");
        assertFalse(maven.toString().contains("secrets."));
    }

    @Test
    void matricesWaitForSameRunDownloadsAndOnlyMainCanSavePersistentCaches() throws IOException {
        var workflow = workflow("compatibility");
        var jobs = mapping(workflow.get("jobs"));
        var warmup = mapping(jobs.get("warm-cache"));
        var security = mapping(jobs.get("dependency-security"));
        assertEquals("warm-cache", security.get("needs"));
        assertEquals("./.github/workflows/security.yml", security.get("uses"));
        assertEquals(Map.of("warmed-dependencies", true), security.get("with"));
        assertNull(security.get("secrets"));
        assertTestedCheckout(warmup);
        assertNull(warmup.get("if"), "cold PRs must also warm dependencies");
        assertNull(warmup.get("continue-on-error"));
        assertEquals("21", mapping(stepUsing(warmup, "actions/setup-java@").get("with")).get("java-version"));
        String command = stepNamed(warmup, "Warm Gradle and TestKit dependencies without running tests").get("run").toString();
        assertTrue(command.contains(":shardpilot-gradle-plugin:warmTestKitCache"));
        var gradleMatrix = mapping(mapping(mapping(jobs.get("compatibility")).get("strategy")).get("matrix"));
        assertTrue(command.contains("-PtestGradleVersions=" + String.join(",",
                sequence(gradleMatrix.get("gradle")).stream().map(Object::toString).toList())));
        assertEquals("python3 -B scripts/ci_dependency_cache.py warm-maven",
                stepNamed(warmup, "Warm Maven reactor and native fixture dependencies without running tests").get("run"));
        for (String step : List.of("Warm Gradle and TestKit dependencies without running tests",
                "Warm Maven reactor and native fixture dependencies without running tests", "Pack downloaded dependencies only")) {
            assertEquals("steps.dependencies.outputs.cache-hit != 'true'", stepNamed(warmup, step).get("if"),
                    "an exact snapshot hit must avoid repeating dependency warmup");
        }
        var save = stepUsing(warmup, "actions/cache/save@");
        assertEquals("github.ref == format('refs/heads/{0}', github.event.repository.default_branch) && steps.dependencies.outputs.cache-hit != 'true'",
                save.get("if"));
        assertEquals("${{ steps.dependencies.outputs.cache-primary-key }}", mapping(save.get("with")).get("key"));
        for (String kind : List.of("Gradle", "Maven")) {
            var snapshot = mapping(stepNamed(warmup, "Share warmed " + kind + " dependencies with this run").get("with"));
            assertEquals(kind.toLowerCase(java.util.Locale.ROOT) + "-dependencies", snapshot.get("name"));
            assertEquals(1, snapshot.get("retention-days"));
            assertEquals("error", snapshot.get("if-no-files-found"));
            assertEquals(true, snapshot.get("overwrite"), "a full rerun must replace the previous warmup artifacts");
        }
        for (String jobName : List.of("compatibility", "maven")) {
            var job = mapping(jobs.get(jobName));
            assertEquals(List.of("warm-cache", "dependency-security"), job.get("needs"));
            assertEquals("${{ !cancelled() }}", job.get("if"));
            var guard = stepNamed(job, "Require successful dependency prerequisites");
            assertEquals("needs.warm-cache.result != 'success' || needs.dependency-security.result != 'success'", guard.get("if"));
            assertTrue(guard.get("run").toString().contains("exit 1"));
            assertEquals(guard, sequence(job.get("steps")).get(0),
                    "a failed warmup must fail required contexts before any cache-dependent work");
            String kind = jobName.equals("compatibility") ? "gradle" : "maven";
            assertEquals(kind + "-dependencies", mapping(stepUsing(job, "actions/download-artifact@").get("with")).get("name"));
            var steps = sequence(job.get("steps"));
            var restore = stepNamed(job, "Unpack downloaded dependencies");
            assertEquals("python3 -B scripts/ci_dependency_cache.py restore " + kind, restore.get("run"));
            assertTrue(steps.indexOf(stepUsing(job, "actions/download-artifact@")) < steps.indexOf(restore));
            assertFalse(job.toString().contains("actions/cache/save@"));
        }
        for (String jobName : List.of("warm-cache", "compatibility")) {
            assertEquals(true, mapping(stepUsing(mapping(jobs.get(jobName)), "gradle/actions/setup-gradle@").get("with")).get("cache-disabled"),
                    "the download snapshot must not overlap another Gradle User Home cache");
        }
        var maven = mapping(jobs.get("maven"));
        assertTrue(sequence(maven.get("steps")).indexOf(stepNamed(maven, "Seed the isolated Invoker repository"))
                < sequence(maven.get("steps")).indexOf(stepNamed(maven, "Verify Maven core, adapter and real runner builds")));
    }

    @Test
    void runtimeSecurityUsesSeparateScopesPinnedScannerAndFreshDatabaseWithoutCredentials() throws Exception {
        var security = workflow("security");
        assertEquals(Map.of("contents", "read"), security.get("permissions"));
        assertFalse(security.toString().contains("secrets."));
        var events = mapping(security.get("on"));
        assertEquals(Set.of("workflow_call", "workflow_dispatch", "schedule"), events.keySet());
        assertFalse(sequence(events.get("schedule")).isEmpty(), "new advisories must also be checked without source changes");
        var callInputs = mapping(mapping(events.get("workflow_call")).get("inputs"));
        assertEquals(false, mapping(callInputs.get("warmed-dependencies")).get("default"));
        var job = mapping(mapping(security.get("jobs")).get("scan"));
        assertEquals(List.of("gradle", "maven"), mapping(mapping(job.get("strategy")).get("matrix")).get("distribution"));
        assertEquals(false, mapping(job.get("strategy")).get("fail-fast"));
        assertTestedCheckout(job);
        for (Object step : sequence(job.get("steps"))) {
            assertNull(mapping(step).get("continue-on-error"), "scanner and DB failures must never turn into success");
        }
        var install = stepUsing(job, "anchore/scan-action/download-grype@");
        assertEquals("anchore/scan-action/download-grype@27805bf3b4e84b4a5c980df22ed233c00390a439", install.get("uses"));
        assertEquals(false, mapping(install.get("with")).get("cache-db"));
        assertEquals("${{ steps.policy.outputs.scanner-release-tag }}", mapping(install.get("with")).get("grype-version"));
        var env = mapping(security.get("env"));
        assertEquals("false", env.get("GRYPE_DB_AUTO_UPDATE"));
        assertEquals("true", env.get("GRYPE_DB_VALIDATE_AGE"));
        String database = stepNamed(job, "Refresh vulnerability database and record scanner identity").get("run").toString();
        assertTrue(database.contains("db update --config security/grype.yaml"));
        assertTrue(database.contains("db status -o json"));
        assertTrue(database.contains("version -o json"));
        String scan = stepNamed(job, "Scan runtime dependencies").get("run").toString();
        assertTrue(scan.contains("sbom:build/security/$DISTRIBUTION/bom.cdx.json"));
        assertTrue(scan.contains("--only-fixed=false --show-suppressed"));
        assertTrue(scan.contains("--fail-on \"$SEVERITY\""));
        assertTrue(scan.contains("scan-status.json"));
        assertTrue(scan.contains("exit \"$scan_exit\""));
        var summary = stepNamed(job, "Summarize findings and verify scan evidence");
        assertTrue(summary.get("if").toString().contains("always()"));
        assertTrue(summary.get("if").toString().contains("steps.scan.outcome == 'failure'"));
        var retained = stepNamed(job, "Retain SBOM, findings and candidate provenance");
        assertEquals("always()", retained.get("if"));
        assertEquals("build/security/${{ matrix.distribution }}/", mapping(retained.get("with")).get("path"));
        assertEquals(30, mapping(retained.get("with")).get("retention-days"));
        var policy = mapping(new Load(LoadSettings.builder().build())
                .loadFromString(Files.readString(Path.of("security/scan-policy.json"))));
        assertEquals("0.121.3", policy.get("scannerVersion"));
        assertEquals("high", policy.get("severityCutoff"));
        assertEquals(true, policy.get("includeUnfixed"));
        assertEquals(120, policy.get("databaseMaxAgeHours"));
    }

    @Test
    void nativeSbomGeneratorsExcludeTestsProvidedApisAndPublicationAttachments() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        String plugin = "/project/profiles/profile[id='security-sbom']/build/plugins/plugin[artifactId='cyclonedx-maven-plugin']";
        assertEquals("2.9.3", xpath.evaluate(plugin + "/version", document));
        for (String included : List.of("includeCompileScope", "includeRuntimeScope", "skipAttach")) {
            assertEquals("true", xpath.evaluate(plugin + "/configuration/" + included, document));
        }
        for (String excluded : List.of("includeProvidedScope", "includeSystemScope", "includeTestScope")) {
            assertEquals("false", xpath.evaluate(plugin + "/configuration/" + excluded, document));
        }
        assertEquals("makeBom", xpath.evaluate(plugin + "/executions/execution/goals/goal", document));
        assertTrue(Files.readString(Path.of("build.gradle")).contains("id 'org.cyclonedx.bom' version '3.5.1'"));
        for (String module : List.of("shardpilot-core", "shardpilot-gradle-plugin")) {
            String build = Files.readString(Path.of(module, "build.gradle"));
            assertTrue(build.contains("includeConfigs = ['runtimeClasspath']"));
            assertTrue(build.contains("includeBuildEnvironment = false"));
        }
    }

    @Test
    void warmupIncludesEveryVersionedLibraryUsedByRealFunctionalFixtures() throws IOException {
        var coordinate = java.util.regex.Pattern.compile("['\"]([\\w.-]+:[\\w.-]+:[\\w.-]+)['\"]");
        try (var sources = Files.list(Path.of("shardpilot-gradle-plugin/src/test/java/io/github/sjacek/shardpilot"))) {
            for (Path source : sources.filter(path -> path.getFileName().toString().endsWith("FunctionalTest.java")).toList()) {
                // The integrity probe serves a harmless synthetic artifact on loopback, never Central.
                if (source.getFileName().toString().equals("DependencyVerificationFunctionalTest.java")) continue;
                var matcher = coordinate.matcher(Files.readString(source));
                while (matcher.find()) {
                    assertTrue(TestKitCacheWarmup.BUILD.contains(matcher.group(1)),
                            () -> "Missing warmup library from " + source + ": " + matcher.group(1));
                }
            }
        }
        String build = Files.readString(Path.of("shardpilot-gradle-plugin/build.gradle"));
        assertTrue(build.contains("systemProperty 'org.gradle.testkit.dir'"));
        assertTrue(build.contains("providers.environmentVariable('SHARDPILOT_TESTKIT_DIR')"));
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
