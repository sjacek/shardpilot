package io.github.sjacek.shardpilot.maven;

import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies actual generated metadata: Java model tests alone cannot prove Maven injects P2 inputs. */
class TestSelectionDescriptorTest {
    @ParameterizedTest
    @ValueSource(strings = {"prepare", "select", "verify", "refresh-durations"})
    void inheritedSelectionParametersAreOptionalAndAvailableToEverySuiteScopedGoal(String goal) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/META-INF/maven/plugin.xml"))) {
            var xml = factory.newDocumentBuilder().parse(input);
            var xpath = XPathFactory.newInstance().newXPath();
            String mojo = "/plugin/mojos/mojo[goal='" + goal + "']/";
            for (String parameter : new String[]{"selectionManifestFile", "selectionSuite"}) {
                assertEquals("${shardpilot." + parameter + "}", xpath.evaluate(mojo + "configuration/" + parameter, xml));
                assertEquals("false", xpath.evaluate(mojo + "parameters/parameter[name='" + parameter + "']/required", xml));
                assertEquals("", xpath.evaluate(mojo + "configuration/" + parameter + "/@default-value", xml),
                        "P2 must not become an implicit default for existing consumers");
            }
            assertEquals("java.io.File", xpath.evaluate(mojo + "parameters/parameter[name='selectionManifestFile']/type", xml));
            assertEquals("java.lang.String", xpath.evaluate(mojo + "parameters/parameter[name='selectionSuite']/type", xml));
            assertEquals("true", xpath.evaluate(mojo + "threadSafe", xml));
            assertEquals("", xpath.evaluate(mojo + "executePhase", xml), "selection must not fork producers during aggregation");
        }
    }
}
