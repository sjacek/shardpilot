package io.github.sjacek.shardpilot.maven;

import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the packaged Maven contract; direct goal calls cannot expose lifecycle or CLI binding regressions. */
class VerifyExecutionsDescriptorTest {
    private static final String GOAL = "/plugin/mojos/mojo[goal='verify-executions']/";

    @Test
    void generatedGoalIsOfflineAndProjectFreeWithoutALifecycleForkOrDependencyResolution() throws Exception {
        var xml = descriptor(); var xpath = XPathFactory.newInstance().newXPath();
        assertEquals(VerifyExecutionsMojo.class.getName(), xpath.evaluate(GOAL + "implementation", xml));
        assertEquals("true", xpath.evaluate(GOAL + "threadSafe", xml));
        for (String flag : List.of("requiresProject", "requiresOnline", "aggregator", "requiresReports")) {
            assertEquals("false", xpath.evaluate(GOAL + flag, xml), flag);
        }
        for (String field : List.of("phase", "executePhase", "executeGoal", "requiresDependencyResolution", "requiresDependencyCollection")) {
            assertEquals("", xpath.evaluate(GOAL + field, xml), field);
        }
    }

    @Test
    void independentInputsAndOutputsAreRequiredAndExposeTheDocumentedCommandLineProperties() throws Exception {
        var xml = descriptor(); var xpath = XPathFactory.newInstance().newXPath();
        for (String name : List.of("expectedRequest", "eligibilityFile", "selectionFile", "nodesDirectory", "reportFile", "htmlReportFile")) {
            assertEquals("true", xpath.evaluate(GOAL + "parameters/parameter[name='" + name + "']/required", xml), name);
            assertEquals(expression(name), xpath.evaluate(GOAL + "configuration/" + name, xml), name);
        }
        // Without a project, callers supply explicit outputs; these are project-local conveniences.
        assertEquals("${project.build.directory}/shardpilot/execution-evidence.json", xpath.evaluate(GOAL + "configuration/reportFile/@default-value", xml));
        assertEquals("${project.build.directory}/shardpilot/execution-evidence.html", xpath.evaluate(GOAL + "configuration/htmlReportFile/@default-value", xml));
    }

    @ParameterizedTest
    @CsvSource({"jsonBytes,1048576", "manifestBytes,1048576", "maxNodes,1024", "maxReports,20000", "maxReportBytes,67108864",
            "maxTotalReportBytes,268435456", "maxDepth,64", "maxTestCases,2000000"})
    void generatedResourceLimitsHaveTheCommonDefaultsAndCanBeLoweredThroughProperties(String parameter, String ceiling) throws Exception {
        var xml = descriptor(); var xpath = XPathFactory.newInstance().newXPath();
        assertEquals(ceiling, xpath.evaluate(GOAL + "configuration/" + parameter + "/@default-value", xml));
        assertEquals(expression(parameter), xpath.evaluate(GOAL + "configuration/" + parameter, xml));
    }

    private static String expression(String parameter) {
        return "${shardpilot.execution" + Character.toUpperCase(parameter.charAt(0)) + parameter.substring(1) + "}";
    }

    private Document descriptor() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var resource = getClass().getResourceAsStream("/META-INF/maven/plugin.xml"); assertNotNull(resource);
        try (resource) { return factory.newDocumentBuilder().parse(resource); }
    }
}
