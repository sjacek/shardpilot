package io.github.sjacek.shardpilot.maven;

import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks generated Maven metadata, including lifecycle isolation that direct Java calls cannot prove. */
class RefreshDurationsDescriptorTest {
    @Test
    void generatedGoalDoesNotBindOrForkTheLifecycleOrResolveDependencies() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/META-INF/maven/plugin.xml"))) {
            var xml = factory.newDocumentBuilder().parse(input);
            var xpath = XPathFactory.newInstance().newXPath();
            String mojo = "/plugin/mojos/mojo[goal='refresh-durations']/";
            assertEquals(RefreshDurationsMojo.class.getName(), xpath.evaluate(mojo + "implementation", xml));
            assertEquals("true", xpath.evaluate(mojo + "threadSafe", xml));
            assertEquals("false", xpath.evaluate(mojo + "aggregator", xml));
            for (String field : List.of("phase", "executePhase", "executeGoal", "requiresDependencyResolution", "requiresDependencyCollection")) {
                assertEquals("", xpath.evaluate(mojo + field, xml), field);
            }
            for (String name : List.of("reports", "outputFile", "reportFile", "baselineFile", "classDirectories",
                    "pruning", "minimumSeconds", "precision", "includeFailedSuites", "duplicates", "minimumCoverage")) {
                assertEquals("${shardpilot." + name + "}", xpath.evaluate(mojo + "configuration/" + name, xml), name);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"pruning,AUTO", "duplicates,FAIL", "minimumSeconds,0.1", "precision,1",
            "includeFailedSuites,false", "minimumCoverage,0.0", "shardCount,1", "runner,SUREFIRE"})
    void generatedDefaultsMatchTheStandaloneGoalContract(String parameter, String expected) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/META-INF/maven/plugin.xml"))) {
            var xml = factory.newDocumentBuilder().parse(input);
            var xpath = XPathFactory.newInstance().newXPath();
            assertEquals(expected, xpath.evaluate("/plugin/mojos/mojo[goal='refresh-durations']/configuration/"
                    + parameter + "/@default-value", xml));
        }
    }
}
