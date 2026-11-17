package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import static org.junit.jupiter.api.Assertions.*;

/** Guards release preparation and its required CI gate without claiming registry publication. */
class ReleaseConsumerReadinessTest {
    @TempDir
    Path directory;

    @Test
    void packagedConsumerTrustRegressionsPass() throws Exception {
        Path log = directory.resolve("release-consumer-trust.log");
        Process process = new ProcessBuilder(System.getProperty("os.name").startsWith("Windows") ? "python" : "python3",
                "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_release_consumers.py", "-v")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Release consumer regression timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void runnableMavenExamplesUseThePreparedVersion() throws Exception {
        String version = System.getProperty("shardpilot.test.releaseVersion");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xpath = XPathFactory.newInstance().newXPath();
        try (var files = Files.walk(Path.of("examples/maven"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("target")).toList()) {
                var model = factory.newDocumentBuilder().parse(file.toFile());
                String property = xpath.evaluate("/project/properties/shardpilot.version", model);
                if (!property.isEmpty()) assertEquals(version, property, file.toString());
                String literal = xpath.evaluate("//plugin[artifactId='shardpilot-maven-plugin']/version", model);
                if (!literal.isEmpty() && !literal.startsWith("${")) assertEquals(version, literal, file.toString());
            }
        }
        assertTrue(Files.readString(Path.of("examples/gradle/junit-jupiter/selection-workflow/build.gradle"))
                .contains("version '" + version + "'"));
    }

    @Test
    void existingRequiredCellRunsFreshConsumersAndRetainsOnlyEvidence() throws Exception {
        var loader = new Load(LoadSettings.builder().setAllowDuplicateKeys(false).build());
        var workflow = (Map<?, ?>) loader.loadFromString(Files.readString(Path.of(".github/workflows/compatibility.yml")));
        var jobs = (Map<?, ?>) workflow.get("jobs");
        var cell = (Map<?, ?>) jobs.get("compatibility");
        var steps = (java.util.List<?>) cell.get("steps");
        var fresh = steps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> "Verify fresh packaged Gradle and Maven consumers".equals(step.get("name"))).findFirst().orElseThrow();
        assertEquals("matrix.gradle == '9.9.1' && matrix.java == '17'", fresh.get("if"));
        String command = fresh.get("run").toString();
        assertTrue(command.contains("scripts/release_consumers.py"));
        assertTrue(command.contains("restore maven"));
        assertFalse(command.contains("--registry-directory"), "Unpublished candidates cannot be registry-audited yet");
        var retained = steps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> "Retain test and validation reports".equals(step.get("name"))).findFirst().orElseThrow();
        String paths = ((Map<?, ?>) retained.get("with")).get("path").toString();
        assertTrue(paths.contains("build/release-consumers/publication.json"));
        assertTrue(paths.contains("build/release-consumers/*-invalid-*/evidence/"));
        assertFalse(paths.contains("build/release-consumers/homes"));
        assertFalse(paths.contains("build/release-consumers/repository"));
    }

    @Test
    void candidateNotesPreserveThePublishedBaselineAndUnassignedPublicationDate() throws Exception {
        String version = System.getProperty("shardpilot.test.releaseVersion");
        String notes = Files.readString(Path.of("docs/releases", version + ".md"));
        assertTrue(notes.contains("Publication is pending"));
        assertTrue(notes.contains("Benchmarking remains deferred"));
        assertTrue(Files.readString(Path.of("CHANGELOG.md")).contains("## [" + version + "]"));
        assertTrue(Files.readString(Path.of("security/published-artifact-audit.json"))
                .contains("\"defaultPublishedVersion\": \"0.5.0\""));
    }
}
