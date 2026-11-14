package io.github.sjacek.shardpilot.maven;

import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The generated descriptor, not handwritten metadata, defines the public Maven C3 boundary. */
class CompiledArtifactsDescriptorTest {
    @ParameterizedTest @ValueSource(strings = {"seal-compiled-artifacts", "verify-compiled-artifacts"})
    void goalsExposeInheritedConfigurationWithoutResolvingDependenciesOrForkingALifecycle(String goal) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var resource = getClass().getResourceAsStream("/META-INF/maven/plugin.xml"); assertNotNull(resource);
        try (resource) {
            var xml = factory.newDocumentBuilder().parse(resource); var xpath = XPathFactory.newInstance().newXPath();
            String base = "/plugin/mojos/mojo[goal='" + goal + "']/";
            assertEquals("true", xpath.evaluate(base + "threadSafe", xml));
            assertEquals("false", xpath.evaluate(base + "requiresProject", xml));
            assertEquals("false", xpath.evaluate(base + "requiresOnline", xml));
            for (String field : List.of("phase", "executePhase", "executeGoal", "requiresDependencyResolution", "requiresDependencyCollection")) assertEquals("", xpath.evaluate(base + field, xml), field);
            for (String name : List.of("compiledRevision", "compiledSuite", "compiledRoots", "compiledRequiredDirectories", "compiledRequiredFiles", "compiledManifestSha256",
                    "compiledMaxFiles", "compiledMaxEntries", "compiledMaxDepth", "compiledMaxFileBytes", "compiledMaxTotalBytes", "compiledMaxManifestBytes")) {
                assertEquals("${shardpilot." + name + "}", xpath.evaluate(base + "configuration/" + name, xml), name);
            }
            for (String name : List.of("compiledBaseDirectory", "compiledManifestFile", "compiledRevision", "compiledSuite", "compiledRoots")) {
                assertEquals("true", xpath.evaluate(base + "parameters/parameter[name='" + name + "']/required", xml), name);
            }
            assertEquals("${project.basedir}", xpath.evaluate(base + "configuration/compiledBaseDirectory/@default-value", xml));
            assertEquals("${project.build.directory}/shardpilot/compiled-artifacts.json", xpath.evaluate(base + "configuration/compiledManifestFile/@default-value", xml));
            for (var limit : Map.of("compiledMaxFiles", "100000", "compiledMaxEntries", "200000", "compiledMaxDepth", "64",
                    "compiledMaxFileBytes", "1073741824", "compiledMaxTotalBytes", "8589934592", "compiledMaxManifestBytes", "33554432").entrySet()) {
                assertEquals(limit.getValue(), xpath.evaluate(base + "configuration/" + limit.getKey() + "/@default-value", xml), limit.getKey());
            }
        }
    }
}
