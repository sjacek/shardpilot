package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import static org.junit.jupiter.api.Assertions.*;

/** Publication bytes must work independently of TestKit/composite development classpaths. */
class PublicationArtifactsTest {
    private final Path publications = Path.of(System.getProperty("shardpilot.test.publicationDirectory"));
    private final String version = System.getProperty("shardpilot.test.releaseVersion");

    @Test
    void implementationAndMarkerKeepOriginalCoordinatesAndResolveOnlyPublishedDependencies() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var parser = factory.newDocumentBuilder();
        var xpath = XPathFactory.newInstance().newXPath();
        var implementation = parser.parse(publications.resolve("pluginMaven/pom-default.xml").toFile());
        var marker = parser.parse(publications.resolve("shardpilotPluginMarkerMaven/pom-default.xml").toFile());
        assertEquals("io.github.sjacek", xpath.evaluate("/project/groupId", implementation));
        assertEquals("shardpilot", xpath.evaluate("/project/artifactId", implementation));
        assertEquals(version, xpath.evaluate("/project/version", implementation));
        assertEquals("io.github.sjacek.shardpilot", xpath.evaluate("/project/groupId", marker));
        assertEquals("io.github.sjacek.shardpilot.gradle.plugin", xpath.evaluate("/project/artifactId", marker));
        assertEquals("shardpilot", xpath.evaluate("/project/dependencies/dependency/artifactId", marker));
        assertEquals(version, xpath.evaluate("/project/dependencies/dependency/version", marker));
        assertEquals("1", xpath.evaluate("count(/project/dependencies/dependency)", implementation));
        assertEquals("snakeyaml-engine", xpath.evaluate("/project/dependencies/dependency/artifactId", implementation));
        assertEquals("runtime", xpath.evaluate("/project/dependencies/dependency/scope", implementation));
        for (var pom : List.of(implementation, marker)) {
            assertEquals("https://github.com/sjacek/shardpilot", xpath.evaluate("/project/url", pom));
            assertEquals("sjacek", xpath.evaluate("/project/developers/developer/id", pom));
            assertEquals("MIT License", xpath.evaluate("/project/licenses/license/name", pom));
            assertEquals("v" + version, xpath.evaluate("/project/scm/tag", pom));
        }
    }

    @Test
    void moduleMetadataAgreesWithThePomAndEveryAdvertisedArtifactExists() throws Exception {
        Map<?, ?> module = (Map<?, ?>) new Load(LoadSettings.builder().build())
                .loadFromString(Files.readString(publications.resolve("pluginMaven/module.json")));
        Map<?, ?> component = (Map<?, ?>) module.get("component");
        assertEquals("shardpilot", component.get("module"));
        assertEquals(version, component.get("version"));
        for (Object value : (List<?>) module.get("variants")) {
            var variant = (Map<?, ?>) value;
            for (Object item : (List<?>) variant.get("files")) {
                var file = (Map<?, ?>) item;
                assertTrue(Files.isRegularFile(Path.of(System.getProperty("shardpilot.test.pluginJar")).getParent().resolve(file.get("name").toString())));
            }
            if (variant.containsKey("dependencies")) {
                for (Object item : (List<?>) variant.get("dependencies")) {
                    assertEquals("snakeyaml-engine", ((Map<?, ?>) item).get("module"));
                }
            }
        }
    }

    @Test
    void binarySourcesAndJavadocContainBothAdapterAndBundledCore() throws Exception {
        Path jar = Path.of(System.getProperty("shardpilot.test.pluginJar"));
        try (var binary = new ZipFile(jar.toFile());
             var sources = new ZipFile(jar.resolveSibling("shardpilot-" + version + "-sources.jar").toFile());
             var javadoc = new ZipFile(jar.resolveSibling("shardpilot-" + version + "-javadoc.jar").toFile())) {
            for (String type : List.of("ShardPilotPlugin", "ShardPlan", "ClassManifest", "ShardCoverage")) {
                String path = "io/github/sjacek/shardpilot/" + type;
                assertNotNull(binary.getEntry(path + ".class"));
                assertNotNull(sources.getEntry(path + ".java"));
                assertNotNull(javadoc.getEntry(path + ".html"));
            }
            assertNotNull(binary.getEntry("META-INF/gradle-plugins/io.github.sjacek.shardpilot.properties"));
            for (var archive : List.of(binary, sources, javadoc)) assertNotNull(archive.getEntry("META-INF/LICENSE"));
        }
    }
}
