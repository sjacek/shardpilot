import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic corruption cases complement the real signed-bundle/GnuPG check in the release gate. */
class VerifyReleaseArtifactsTest {
    private static final String VERSION = "0.3.0";
    private Map<String, byte[]> bundle;
    private byte[] effective;

    @BeforeEach
    void completeCohort() throws Exception {
        bundle = new LinkedHashMap<>();
        var projects = new StringBuilder("<projects>");
        for (String artifact : List.of("shardpilot-parent", "shardpilot-core", "shardpilot-maven-plugin")) {
            String prefix = prefix(artifact);
            String pom = pom(artifact);
            projects.append(pom);
            attach(prefix + ".pom", bytes(pom));
            if (!artifact.equals("shardpilot-parent")) {
                boolean core = artifact.equals("shardpilot-core");
                String type = core ? "io/github/sjacek/shardpilot/ShardPlan" : "io/github/sjacek/shardpilot/maven/SelectMojo";
                var binary = new LinkedHashMap<String, byte[]>();
                binary.put(type + ".class", new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 61});
                binary.put("META-INF/LICENSE", bytes("MIT License"));
                if (!core) binary.put("META-INF/maven/plugin.xml", bytes("""
                        <plugin><version>0.3.0</version><goalPrefix>shardpilot</goalPrefix><mojos>
                          <mojo><goal>prepare</goal></mojo><mojo><goal>select</goal></mojo><mojo><goal>verify</goal></mojo>
                          <mojo><goal>refresh-durations</goal></mojo>
                        </mojos></plugin>
                        """));
                attach(prefix + ".jar", zip(binary));
                attach(prefix + "-sources.jar", zip(Map.of(type + ".java", bytes("// Main API source"))));
                attach(prefix + "-javadoc.jar", zip(Map.of("index.html", bytes("API index"), type + ".html", bytes("Public API"))));
            }
        }
        effective = bytes(projects.append("</projects>").toString());
    }

    @Test
    void completeCohortPassesAndRetainsAllNineSignedArtifacts() throws Exception {
        VerifyReleaseArtifacts.verify(bundle, VERSION, effective);
        assertEquals(9, bundle.keySet().stream().filter(name -> name.endsWith(".asc")).count());
    }

    @Test
    void releaseWithoutHistoryRefreshGoalCannotPass() throws Exception {
        String path = prefix("shardpilot-maven-plugin") + ".jar";
        var binary = VerifyReleaseArtifacts.readArchive(bundle.get(path));
        var incomplete = new LinkedHashMap<>(binary);
        String descriptor = new String(binary.get("META-INF/maven/plugin.xml"), StandardCharsets.UTF_8)
                .replace("<mojo><goal>refresh-durations</goal></mojo>", "");
        incomplete.put("META-INF/maven/plugin.xml", bytes(descriptor));
        attach(path, zip(incomplete)); // Valid checksums isolate the descriptor contract.
        var failure = assertThrows(IllegalArgumentException.class,
                () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
        assertEquals("Missing Maven plugin goals", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-sources.jar", "-javadoc.jar", ".pom.asc", ".jar.sha256"})
    void missingReleaseAttachmentsCannotPass(String suffix) {
        bundle.remove(prefix("shardpilot-maven-plugin") + suffix);
        assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
    }

    @Test
    void alteredArtifactCannotPassItsOriginalChecksums() {
        bundle.put(prefix("shardpilot-core") + ".jar", bytes("changed after signing"));
        var failure = assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
        assertTrue(failure.getMessage().contains("Checksum mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shardpilot-parent", "shardpilot-core", "shardpilot-maven-plugin"})
    void inheritedHomepageCannotReplaceAnExplicitPublishedHomepage(String artifact) throws Exception {
        String published = pom(artifact).replaceFirst("<url>https://github.com/sjacek/shardpilot</url>", "");
        // Recompute checksums so the failure concerns raw POM metadata, not artifact corruption.
        attach(prefix(artifact) + ".pom", bytes(published));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
        assertEquals("Published project homepage is missing or incorrect: " + artifact, failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shardpilot-parent", "shardpilot-core", "shardpilot-maven-plugin"})
    void incorrectPublishedHomepageCannotPassWithAValidEffectiveModel(String artifact) throws Exception {
        String published = pom(artifact).replaceFirst("<url>https://github.com/sjacek/shardpilot</url>",
                "<url>https://github.com/sjacek/shardpilot/" + artifact + "</url>");
        attach(prefix(artifact) + ".pom", bytes(published));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
        assertEquals("Published project homepage is missing or incorrect: " + artifact, failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"developer", "scm", "version"})
    void invalidEffectiveMetadataCannotPass(String kind) {
        String model = new String(effective, StandardCharsets.UTF_8);
        model = switch (kind) {
            case "developer" -> model.replaceAll("<developers>.*?</developers>", "");
            case "scm" -> model.replace("scm:git:https://github.com/sjacek/shardpilot.git", "scm:git:https://github.com/sjacek/shardpilot.git/shardpilot-core");
            default -> model.replace("<version>0.3.0</version>", "<version>0.3.1</version>");
        };
        byte[] invalid = bytes(model);
        assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.verify(bundle, VERSION, invalid));
    }

    @Test
    void foreignArtifactsAreRejectedEvenWhenTheRequiredCohortIsComplete() {
        bundle.put("io/github/sjacek/another-plugin/0.3.0/another-plugin-0.3.0.jar", bytes("foreign"));
        assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.verify(bundle, VERSION, effective));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "..\\escape", "/absolute", "C:/absolute", "nested/../../escape"})
    void unsafeArchivePathsAreRejectedBeforeExtraction(String path) throws Exception {
        byte[] archive = zip(Map.of(path, bytes("untrusted")));
        assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.readArchive(archive));
    }

    @Test
    void hostSeparatorsAreNormalizedAndCannotHideDuplicatePaths() throws Exception {
        assertTrue(VerifyReleaseArtifacts.readArchive(zip(Map.of("safe\\file.pom", bytes("metadata")))).containsKey("safe/file.pom"));
        byte[] collision = zip(Map.of("safe/file.pom", bytes("first"), "safe\\file.pom", bytes("second")));
        assertThrows(IllegalArgumentException.class, () -> VerifyReleaseArtifacts.readArchive(collision));
    }

    @Test
    void doctypesCannotResolveExternalFilesInPublicationMetadata() {
        byte[] unsafe = bytes("<!DOCTYPE projects [<!ENTITY secret SYSTEM 'file:///not-readable'>]><projects>&secret;</projects>");
        assertThrows(Exception.class, () -> VerifyReleaseArtifacts.xml(unsafe));
    }

    /** Placeholder signatures test shape only; the real CI fixture verifies cryptography with GnuPG. */
    private void attach(String file, byte[] contents) throws Exception {
        bundle.put(file, contents);
        bundle.put(file + ".asc", bytes("-----BEGIN PGP SIGNATURE-----\nunit fixture\n-----END PGP SIGNATURE-----\n"));
        for (var algorithm : Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512").entrySet()) {
            bundle.put(file + "." + algorithm.getKey(), bytes(HexFormat.of().formatHex(MessageDigest.getInstance(algorithm.getValue()).digest(contents))));
        }
    }

    private static String prefix(String artifact) { return "io/github/sjacek/" + artifact + "/0.3.0/" + artifact + "-0.3.0"; }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String pom(String artifact) {
        String parent = artifact.equals("shardpilot-parent") ? "" : "<parent><groupId>io.github.sjacek</groupId><artifactId>shardpilot-parent</artifactId><version>0.3.0</version></parent>";
        return """
                <project child.project.url.inherit.append.path="false">%s
                <groupId>io.github.sjacek</groupId><artifactId>%s</artifactId><version>0.3.0</version>
                <name>ShardPilot</name><description>Parallel JVM test sharding</description><url>https://github.com/sjacek/shardpilot</url>
                <developers><developer><name>sjacek</name><url>https://github.com/sjacek</url></developer></developers>
                <licenses><license><name>MIT License</name><url>https://opensource.org/license/mit</url></license></licenses>
                <scm child.scm.connection.inherit.append.path="false" child.scm.developerConnection.inherit.append.path="false" child.scm.url.inherit.append.path="false">
                <connection>scm:git:https://github.com/sjacek/shardpilot.git</connection>
                <developerConnection>scm:git:ssh://git@github.com/sjacek/shardpilot.git</developerConnection>
                <url>https://github.com/sjacek/shardpilot</url><tag>v0.3.0</tag></scm></project>
                """.formatted(parent, artifact);
    }
}
