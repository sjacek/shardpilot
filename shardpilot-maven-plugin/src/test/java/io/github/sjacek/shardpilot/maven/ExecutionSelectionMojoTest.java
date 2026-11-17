package io.github.sjacek.shardpilot.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Descriptor injection and both artifact-only goals share independently configured C3 inputs. */
class ExecutionSelectionMojoTest {
    @TempDir
    Path directory;

    @BeforeEach
    void fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("docs/examples/execution-selection-testdata"))) root = root.getParent();
        Path source = root.resolve("docs/examples/execution-selection-testdata");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = directory.resolve(source.relativize(file));
                Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
    }

    @Test
    void producerAndVerifierUseTheSameCanonicalSelectionWithoutAProjectOrSession() throws Exception {
        var producer = producer(); producer.execute();
        assertEquals(Files.readString(directory.resolve("selection.json")), Files.readString(directory.resolve("generated.json")));
        var verifier = verifier(); verifier.execute();
        assertTrue(Files.readString(directory.resolve("out.json")).contains("\"selectionProvenance\""));
        assertTrue(Files.readString(directory.resolve("out.json")).contains("\"status\":\"PASSED\""));
        assertFalse(Files.exists(directory.resolve("target")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"compiled/classes/synthetic-testdata.txt", "compiled-manifest.json"})
    void changedBytesInvalidateOldProducerOutputAndReplaceAggregateSuccess(String file) throws Exception {
        var producer = producer(); var verifier = verifier(); producer.execute(); verifier.execute();
        Files.writeString(directory.resolve(file), "PRIVATE_CHANGED");
        var error = assertThrows(MojoFailureException.class, producer::execute);
        assertFalse(error.getMessage().contains("PRIVATE")); assertNull(error.getCause());
        assertFalse(Files.exists(directory.resolve("generated.json")));
        assertThrows(MojoFailureException.class, verifier::execute);
        assertTrue(Files.readString(directory.resolve("out.json")).contains("COMPILED_ARTIFACT_MISMATCH"));
        assertFalse(Files.readString(directory.resolve("out.json")).contains("PRIVATE"));
    }

    @Test
    void missingOrPartialC3ConfigurationCannotSilentlyDisableVerification() throws Exception {
        var goal = verifier();
        set(goal, "compiledBaseDirectory", null);
        var error = assertThrows(MojoFailureException.class, goal::execute);
        assertNull(error.getCause()); assertFalse(Files.exists(directory.resolve("out.json")));
    }

    @Test
    void unresolvedOptionalListsAndPinDoNotBreakAnOtherwiseDisabledC3Gate() throws Exception {
        var goal = new PrepareExecutionSelectionMojo();
        for (String field : List.of("compiledRoots", "compiledRequiredDirectories", "compiledRequiredFiles", "compiledManifestSha256")) set(goal, field, null);
        assertNull(goal.compiledInputs(null, null));
    }

    @Test
    void producerCanDefaultCompiledIdentityOnlyFromItsIndependentlyConfiguredSelection() throws Exception {
        var producer = producer(); set(producer, "compiledRevision", null); set(producer, "compiledSuite", null);
        producer.execute();
        assertEquals(Files.readString(directory.resolve("selection.json")), Files.readString(directory.resolve("generated.json")));
    }

    @Test
    void aggregateCannotAdoptRevisionOrSuiteFromDownloadedSelectionMetadata() throws Exception {
        var verifier = verifier(); set(verifier, "compiledRevision", null); set(verifier, "compiledSuite", null);
        var failure = assertThrows(MojoFailureException.class, verifier::execute);
        assertNull(failure.getCause()); assertFalse(Files.exists(directory.resolve("out.json")));
        assertEquals("Invalid ShardPilot execution-evidence configuration", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"compiledMaxFileBytes", "compiledMaxTotalBytes", "compiledMaxManifestBytes"})
    void loweredCompiledLimitsAreEnforcedByBothGoalsInsteadOfReusingSuccess(String parameter) throws Exception {
        var producer = producer(); var verifier = verifier(); producer.execute(); verifier.execute();
        Object limit;
        if (parameter.equals("compiledMaxManifestBytes")) limit = 1;
        else limit = 1L;
        set(producer, parameter, limit); set(verifier, parameter, limit);
        assertThrows(MojoFailureException.class, producer::execute); assertFalse(Files.exists(directory.resolve("generated.json")));
        assertThrows(MojoFailureException.class, verifier::execute);
        assertTrue(Files.readString(directory.resolve("out.json")).contains("COMPILED_ARTIFACT_MISMATCH"));
        assertTrue(Files.readString(directory.resolve("out.html")).contains("execution evidence: FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"surefire", "failsafe"})
    void boundCohortKeepsMavenRunnerIsolationAndRejectsMixedNodeEvidence(String runner) throws Exception {
        for (String name : List.of("expected.json", "nodes/node-1/evidence.json", "nodes/node-2/evidence.json")) {
            Path file = directory.resolve(name);
            Files.writeString(file, Files.readString(file).replace("\"tool\":\"gradle\"", "\"tool\":\"maven\"")
                    .replace("\"runner\":\"gradle-test\"", "\"runner\":\"" + runner + "\""));
        }
        var verifier = verifier(); verifier.execute();
        assertTrue(Files.readString(directory.resolve("out.json")).contains("\"schemaVersion\":2"));
        assertTrue(Files.readString(directory.resolve("out.json")).contains("\"nativeProcess\":\"PASSED\""));
        Path foreign = directory.resolve("nodes/node-2/evidence.json");
        Files.writeString(foreign, Files.readString(foreign).replace("\"runner\":\"" + runner + "\"",
                "\"runner\":\"" + (runner.equals("surefire") ? "failsafe" : "surefire") + "\""));
        assertThrows(MojoFailureException.class, verifier::execute);
        assertTrue(Files.readString(directory.resolve("out.json")).contains("COHORT_MISMATCH"));
    }

    @Test
    void producerOutputIoFailureOmitsPrivateFilesystemCausesAndPreservesTrustedSelection() throws Exception {
        var producer = producer(); Path blocker = directory.resolve("PRIVATE_PARENT"); Files.writeString(blocker, "retained blocker");
        set(producer, "selectionFile", blocker.resolve("execution.json").toFile());
        String original = Files.readString(directory.resolve("selection.json"));
        var failure = assertThrows(MojoExecutionException.class, producer::execute);
        assertEquals("Cannot write ShardPilot execution selection", failure.getMessage()); assertNull(failure.getCause());
        assertEquals("retained blocker", Files.readString(blocker)); assertEquals(original, Files.readString(directory.resolve("selection.json")));
    }

    @Test
    void outputCannotOverwriteAnInventoriedFile() throws Exception {
        var producer = producer(); Path input = directory.resolve("compiled/classes/synthetic-testdata.txt");
        String original = Files.readString(input); set(producer, "selectionFile", input.toFile());
        assertThrows(MojoFailureException.class, producer::execute); assertEquals(original, Files.readString(input));
        var verifier = verifier(); set(verifier, "reportFile", input.toFile());
        assertThrows(MojoFailureException.class, verifier::execute); assertEquals(original, Files.readString(input));
    }

    @Test
    void packagedGoalAndInheritedParametersHaveNoLifecycleOrOnlineRequirements() throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var stream = getClass().getResourceAsStream("/META-INF/maven/plugin.xml"); assertNotNull(stream);
        try (stream) {
            var xml = factory.newDocumentBuilder().parse(stream); var xpath = XPathFactory.newInstance().newXPath();
            for (String goal : List.of("prepare-execution-selection", "verify-executions")) {
                String base = "/plugin/mojos/mojo[goal='" + goal + "']/";
                assertEquals("true", xpath.evaluate(base + "threadSafe", xml));
                for (String name : List.of("requiresProject", "requiresOnline")) assertEquals("false", xpath.evaluate(base + name, xml));
                for (String name : List.of("phase", "executePhase", "requiresDependencyResolution", "requiresDependencyCollection")) assertEquals("", xpath.evaluate(base + name, xml));
                for (String name : List.of("ManifestFile", "BaseDirectory", "Revision", "Suite", "Roots", "RequiredDirectories", "RequiredFiles", "ManifestSha256",
                        "MaxFiles", "MaxEntries", "MaxDepth", "MaxFileBytes", "MaxTotalBytes", "MaxManifestBytes")) {
                    assertEquals("${shardpilot.executionCompiled" + name + "}", xpath.evaluate(base + "configuration/compiled" + name, xml));
                }
            }
            String producer = "/plugin/mojos/mojo[goal='prepare-execution-selection']/";
            for (var input : Map.of("snapshotDirectory", "selectionSnapshotDirectory", "policyFile", "selectionPolicyFile",
                    "eligibilityFile", "executionEligibilityFile", "profile", "selectionProfile", "revision", "executionRevision",
                    "selectionFile", "executionSelectionFile").entrySet()) {
                assertEquals("true", xpath.evaluate(producer + "parameters/parameter[name='" + input.getKey() + "']/required", xml));
                assertEquals("${shardpilot." + input.getValue() + "}", xpath.evaluate(producer + "configuration/" + input.getKey(), xml));
            }
            for (String optional : List.of("historyFile", "explicitManifestFile", "suite")) {
                assertEquals("false", xpath.evaluate(producer + "parameters/parameter[name='" + optional + "']/required", xml));
            }
            assertEquals("1", xpath.evaluate(producer + "configuration/shardCount/@default-value", xml));
            assertEquals("${project.build.directory}/shardpilot/execution-selection.json", xpath.evaluate(producer + "configuration/selectionFile/@default-value", xml));
        }
    }

    private PrepareExecutionSelectionMojo producer() throws Exception {
        var goal = new PrepareExecutionSelectionMojo();
        set(goal, "snapshotDirectory", directory.resolve("snapshot").toFile());
        set(goal, "policyFile", directory.resolve("policy.yaml").toFile());
        set(goal, "eligibilityFile", directory.resolve("baseline.yaml").toFile());
        set(goal, "historyFile", directory.resolve("history.tsv").toFile());
        set(goal, "profile", "smoke-testdata"); set(goal, "revision", "a".repeat(40)); set(goal, "shardCount", 2);
        set(goal, "selectionFile", directory.resolve("generated.json").toFile());
        compiled(goal); return goal;
    }

    private VerifyExecutionsMojo verifier() throws Exception {
        var goal = new VerifyExecutionsMojo();
        set(goal, "expectedRequest", directory.resolve("expected.json").toFile());
        set(goal, "eligibilityFile", directory.resolve("baseline.yaml").toFile());
        set(goal, "selectionFile", directory.resolve("selection.json").toFile());
        set(goal, "nodesDirectory", directory.resolve("nodes").toFile());
        set(goal, "reportFile", directory.resolve("out.json").toFile()); set(goal, "htmlReportFile", directory.resolve("out.html").toFile());
        compiled(goal); return goal;
    }

    private void compiled(AbstractMojo goal) throws Exception {
        set(goal, "compiledManifestFile", directory.resolve("compiled-manifest.json").toFile());
        set(goal, "compiledBaseDirectory", directory.resolve("compiled").toFile());
        set(goal, "compiledRevision", "a".repeat(40)); set(goal, "compiledSuite", "profile-smoke-testdata");
        set(goal, "compiledRoots", List.of("classes")); set(goal, "compiledRequiredDirectories", List.of("classes"));
        set(goal, "compiledRequiredFiles", List.of("classes/synthetic-testdata.txt"));
    }

    private static void set(AbstractMojo goal, String name, @org.jspecify.annotations.Nullable Object value) throws Exception {
        Class<?> type = goal.getClass();
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(goal, value); return; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
