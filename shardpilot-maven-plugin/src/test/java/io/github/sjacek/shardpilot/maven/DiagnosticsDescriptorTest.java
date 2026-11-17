package io.github.sjacek.shardpilot.maven;

import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The generated descriptor keeps C4 opt-in, project-free and independent from native lifecycles. */
class DiagnosticsDescriptorTest {
    @ParameterizedTest @ValueSource(strings = {"prepare-diagnostics", "verify-diagnostics"})
    void standaloneGoalsExposeIdentityAndSamplingWithoutResolvingDependencies(String goal) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var resource = getClass().getResourceAsStream("/META-INF/maven/plugin.xml"); assertNotNull(resource);
        try (resource) {
            var document = factory.newDocumentBuilder().parse(resource); var xpath = XPathFactory.newInstance().newXPath();
            String root = "/plugin/mojos/mojo[goal='" + goal + "']/";
            assertEquals("true", xpath.evaluate(root + "threadSafe", document));
            assertEquals("false", xpath.evaluate(root + "requiresProject", document)); assertEquals("false", xpath.evaluate(root + "requiresOnline", document));
            for (String field : List.of("phase", "executePhase", "executeGoal", "requiresDependencyResolution", "requiresDependencyCollection")) assertEquals("", xpath.evaluate(root + field, document));
            for (String required : List.of("diagnosticsConfigurationFile", "diagnosticsDirectory", "diagnosticsRevision", "diagnosticsRunId", "diagnosticsAttempt", "diagnosticsSuite", "diagnosticsNode")) {
                assertEquals("true", xpath.evaluate(root + "parameters/parameter[name='" + required + "']/required", document));
                assertEquals("${shardpilot." + required + "}", xpath.evaluate(root + "configuration/" + required, document));
            }
            // Check the shipped descriptor, not just Java field names: every documented optional
            // setting must remain reachable through Maven's property-injection boundary too.
            for (String optional : List.of("diagnosticsTimeoutMillis", "diagnosticsSampleIntervalMillis", "diagnosticsMaxSamples",
                    "diagnosticsJobCgroup", "diagnosticsDindCgroup", "diagnosticsHostMemory", "diagnosticsDockerCommand",
                    "diagnosticsDockerHost", "diagnosticsDedicatedDocker")) {
                assertEquals("${shardpilot." + optional + "}", xpath.evaluate(root + "configuration/" + optional, document));
            }
            assertEquals("0", xpath.evaluate(root + "configuration/diagnosticsTimeoutMillis/@default-value", document));
            assertEquals("1000", xpath.evaluate(root + "configuration/diagnosticsSampleIntervalMillis/@default-value", document));
            assertEquals("10000", xpath.evaluate(root + "configuration/diagnosticsMaxSamples/@default-value", document));
            assertEquals("false", xpath.evaluate(root + "configuration/diagnosticsDedicatedDocker/@default-value", document));
            assertEquals("false", xpath.evaluate(root + "configuration/diagnosticsHostMemory/@default-value", document));
        }
    }
}
