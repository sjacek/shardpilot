package io.github.sjacek.shardpilot.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Project-free direct calls complement Invoker's native Surefire/Failsafe ordering checks. */
class CompiledArtifactsMojoTest {
    @TempDir Path directory;
    private SealCompiledArtifactsMojo seal;
    private VerifyCompiledArtifactsMojo verify;

    @BeforeEach void fixture() throws Exception {
        Files.createDirectories(directory.resolve("downloaded/classes"));
        Files.writeString(directory.resolve("downloaded/classes/Example.class"), "bytecode");
        Files.writeString(directory.resolve("downloaded/config.txt"), "PRIVATE_RESOURCE");
        seal = configure(new SealCompiledArtifactsMojo()); verify = configure(new VerifyCompiledArtifactsMojo());
    }

    @Test void verifiesImmutableBytesWithoutCompilingOrProducingNativeReports() throws Exception {
        seal.execute(); byte[] before = Files.readAllBytes(directory.resolve("metadata/compiled.json"));
        verify.execute(); verify.execute();
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("metadata/compiled.json")));
        assertFalse(Files.exists(directory.resolve("target")));
    }

    @ParameterizedTest @CsvSource({"revision,REVISION_MISMATCH", "suite,SUITE_MISMATCH", "digest,MANIFEST_DIGEST_MISMATCH",
            "changed,FILE_CONTENT_MISMATCH", "missing,REQUIRED_ARTIFACT_MISSING", "extra,FILE_SET_MISMATCH", "policy,POLICY_MISMATCH"})
    void integrityFailuresFailTheGoalWithoutPrivatePayloadsOrExceptionCauses(String kind, String reason) throws Exception {
        seal.execute();
        switch (kind) {
            case "revision" -> verify.compiledRevision = "b".repeat(40);
            case "suite" -> verify.compiledSuite = "other";
            case "digest" -> verify.compiledManifestSha256 = "0".repeat(64);
            case "changed" -> Files.writeString(directory.resolve("downloaded/config.txt"), "CHANGED_RESOURCE");
            case "missing" -> Files.delete(directory.resolve("downloaded/classes/Example.class"));
            case "extra" -> Files.writeString(directory.resolve("downloaded/classes/Stale.class"), "stale");
            default -> verify.compiledRequiredFiles = List.of();
        }
        var failure = assertThrows(MojoFailureException.class, verify::execute);
        assertEquals("C3 " + reason, failure.getMessage()); assertFalse(failure.getMessage().contains("PRIVATE")); assertNull(failure.getCause());
        assertFalse(Files.exists(directory.resolve("target")));
    }

    @Test void missingManifestCannotBeReconstructedByTheVerificationGoal() {
        assertThrows(MojoFailureException.class, verify::execute);
        assertFalse(Files.exists(directory.resolve("metadata")));
    }

    @Test void unresolvedOptionalListExpressionsUseTheDocumentedEmptyRequirements() throws Exception {
        seal.compiledRequiredDirectories = null; seal.compiledRequiredFiles = null;
        verify.compiledRequiredDirectories = null; verify.compiledRequiredFiles = null;
        seal.execute(); verify.execute();
        assertFalse(Files.exists(directory.resolve("target")));
    }

    @Test void failedResealCannotLeaveAnEarlierSuccessfulManifest() throws Exception {
        seal.execute(); Files.delete(directory.resolve("downloaded/config.txt"));
        assertThrows(MojoFailureException.class, seal::execute);
        assertFalse(Files.exists(directory.resolve("metadata/compiled.json")));
    }

    @Test void loweredStreamingLimitsAndInvalidLimitConfigurationFailTheGoal() {
        seal.compiledMaxFileBytes = 1;
        assertThrows(MojoFailureException.class, seal::execute);
        seal.compiledMaxFileBytes = 0;
        assertEquals("C3 INVALID_LIMITS", assertThrows(MojoFailureException.class, seal::execute).getMessage());
    }

    private <T extends AbstractCompiledArtifactsMojo> T configure(T goal) {
        goal.compiledBaseDirectory = directory.toFile(); goal.compiledManifestFile = directory.resolve("metadata/compiled.json").toFile();
        goal.compiledRevision = "a".repeat(40); goal.compiledSuite = "example"; goal.compiledRoots = List.of("downloaded");
        goal.compiledRequiredDirectories = List.of("downloaded/classes"); goal.compiledRequiredFiles = List.of("downloaded/config.txt");
        return goal;
    }
}
