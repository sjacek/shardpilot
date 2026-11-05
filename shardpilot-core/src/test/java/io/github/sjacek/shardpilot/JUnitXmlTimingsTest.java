package io.github.sjacek.shardpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JUnitXmlTimingsTest {
    @TempDir
    Path directory;

    @Test
    void readsBothRootsWithMetadataButWithoutDiagnosticPayloads() throws Exception {
        Path first = xml("first/TEST-a.xml", """
                <testsuite name="example.Outer" tests="0" time="0.9">
                  <testcase classname="example.Outer" name="outer"/>
                  <testcase classname="example.Outer$Inner" name="nested"/>
                  <system-out>SECRET OUTPUT</system-out>
                </testsuite>
                """);
        Path second = xml("second/TEST-b.xml", """
                <testsuites><testsuite name="example.Flaky" tests="1" time="0.2">
                  <testcase name="flaky"><flakyFailure message="SECRET MESSAGE">SECRET STACK</flakyFailure></testcase>
                </testsuite></testsuites>
                """);
        var suites = JUnitXmlTimings.read(List.of(first.getParent(), second));
        assertEquals(2, suites.size());
        assertTrue(suites.get(0).executed());
        assertEquals(java.util.Set.of("example.Outer", "example.Outer$Inner"), suites.get(0).members());
        assertTrue(suites.get(1).retried());
        assertFalse(suites.toString().contains("SECRET"));
        assertEquals(suites, JUnitXmlTimings.read(List.of(second, first.getParent())));
        assertEquals(2, JUnitXmlTimings.read(List.of(second, first.getParent(), first)).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-1", "secret-not-a-number"})
    void rejectsInvalidTimesWithoutEchoingValues(String time) throws Exception {
        Path file = xml("TEST-invalid.xml", "<testsuite name=\"example.Bad\" time=\"" + time + "\"/>");
        var failure = assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(file)));
        assertTrue(failure.getMessage().contains("<testsuite>"));
        assertTrue(failure.getMessage().endsWith("invalid finite nonnegative time"));
        if (time.startsWith("secret")) assertFalse(failure.getMessage().contains(time));
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<testsuite name='example.Valid'/>",
            "<testsuite name='invalid/name' time='1'/>",
            "<testsuite name='example.Valid' time='1' tests='-1'/>",
            "<testsuite name='example.Valid' time='1' errors='NaN'/>",
            "<testsuite name='example.Valid' time='1'><testcase/></testsuite>",
            "<unknown/>"})
    void rejectsInvalidSuiteMetadata(String xml) throws Exception {
        Path file = xml("TEST-invalid.xml", xml);
        assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(file)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE testsuite [<!ENTITY secret 'SECRET'>]>",
            "<!DOCTYPE testsuite SYSTEM 'file:///SECRET'>",
            "<!DOCTYPE testsuite [<!ENTITY secret SYSTEM 'http://127.0.0.1:1/SECRET'>]>"})
    void refusesDtdAndExternalEntities(String declaration) throws Exception {
        Path file = xml("TEST-xxe.xml", declaration + "<testsuite name='example.Safe' time='1'/>");
        var failure = assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(file)));
        assertFalse(failure.toString().contains("SECRET"));
        assertNull(failure.getCause());
    }

    @Test
    void classifiesSkippedAndAmbiguousCasesAndRetainsCompletedSuitesOnParseFailure() throws Exception {
        Path good = xml("TEST-a.xml", """
                <testsuites>
                  <testsuite name="example.Skipped" tests="2" time="0.01">
                    <testcase name="first"><skipped/></testcase><testcase name="second"><skipped/></testcase>
                  </testsuite>
                  <testsuite name="example.Repeated" tests="2" time="1">
                    <testcase name="same"><failure message="SECRET"/></testcase><testcase name="same"/>
                  </testsuite>
                </testsuites>
                """);
        var parsed = JUnitXmlTimings.read(List.of(good));
        assertFalse(parsed.get(0).executed());
        assertTrue(parsed.get(1).failed());
        assertTrue(parsed.get(1).ambiguous());
        assertFalse(parsed.get(1).retried());
        Path bad = xml("TEST-z.xml", "<testsuite name='example.Broken' time='SECRET'/>");
        var failure = assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(good, bad)));
        assertEquals(2, failure.partial().size());
        assertFalse(failure.getMessage().contains("SECRET"));
    }

    @Test
    void ignoresUnrelatedXmlAndMissingReportDirectories() throws Exception {
        xml("other.xml", "not XML");
        assertTrue(JUnitXmlTimings.read(List.of(directory, directory.resolve("missing"))).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flakyFailure", "flakyError", "rerunFailure", "rerunError"})
    void everyRetryMarkerExcludesTheSampleEvenWhenFailureInclusionIsEnabled(String marker) throws Exception {
        Path file = xml("TEST-retry.xml", "<testsuite name='example.Retry' tests='1' time='1'>"
                + "<testcase name='same'><" + marker + " message='SECRET'/></testcase></testsuite>");
        var suites = JUnitXmlTimings.read(List.of(file));
        assertTrue(suites.get(0).retried());
        var result = DurationHistoryRefresh.refresh(java.util.Map.of(), suites, DurationHistoryRefresh.Eligibility.disabled(),
                new DurationHistoryRefresh.Options(.1, 1, true, DurationHistoryRefresh.Duplicates.FAIL, 0, 1));
        assertFalse(result.passed());
        assertEquals(1, result.report().counts().retried());
        assertFalse(result.report().json().contains("SECRET"));
    }

    @Test
    void aggregateSuiteNestingIsRejectedRatherThanDoubleCounted() throws Exception {
        Path file = xml("TEST-aggregate.xml", "<testsuite name='example.Outer' time='2'><testsuite name='example.Outer$Inner' time='1'/></testsuite>");
        assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(file)));
    }

    @Test
    void ignoresStructuredPayloadsThatImitateSuiteCaseAndRetryMetadata() throws Exception {
        Path file = xml("TEST-payload.xml", """
                <testsuite name="example.Real" time="2" tests="1">
                  <properties><testcase classname="invalid/name" name="PAYLOAD_SECRET"/></properties>
                  <testcase classname="example.Real" name="actual">
                    <failure message="PAYLOAD_SECRET"><flakyFailure/><skipped/>
                      <testcase classname="invalid/name" name="PAYLOAD_SECRET"/>
                    </failure>
                  </testcase>
                  <system-out><testsuite name="invalid/name" time="NaN"/>
                    <testcase name="PAYLOAD_SECRET"/>
                  </system-out>
                  <system-err><![CDATA[PAYLOAD_SECRET <testcase/>]]></system-err>
                </testsuite>
                """);
        var suites = JUnitXmlTimings.read(List.of(file));
        assertEquals(1, suites.size());
        var suite = suites.get(0);
        assertEquals(1, suite.cases().size());
        assertEquals("actual", suite.cases().get(0).name());
        assertTrue(suite.executed()); assertTrue(suite.failed());
        assertFalse(suite.retried()); assertFalse(suite.ambiguous());
        assertFalse(suites.toString().contains("PAYLOAD_SECRET"));
    }

    @Test
    void malformedTailRetainsOnlyCompletelyClosedSuitesFromTheSameFile() throws Exception {
        Path file = xml("TEST-partial.xml", """
                <testsuites>
                  <testsuite name="example.Complete" tests="1" time="1"><testcase name="ok"/></testsuite>
                  <testsuite name="example.Partial" tests="1" time="2"><testcase name="PARTIAL_SECRET">
                """);
        var failure = assertThrows(JUnitXmlTimings.ReadFailure.class, () -> JUnitXmlTimings.read(List.of(file)));
        assertEquals(List.of("example.Complete"), failure.partial().stream().map(JUnitXmlTimings.Suite::name).toList());
        assertFalse(failure.toString().contains("PARTIAL_SECRET"));
        assertFalse(failure.partial().toString().contains("PARTIAL_SECRET"));
        // Deliberate mutation attempt verifies the immutable failure snapshot contract.
        //noinspection DataFlowIssue
        assertThrows(UnsupportedOperationException.class, () -> failure.partial().clear());
    }

    @Test
    void acceptsNamespacedReportsAndSnapshotsTestcaseMetadata() throws Exception {
        Path file = xml("TEST-namespace.xml", """
                <testsuites xmlns="urn:fixture">
                  <testsuite name="example.Żółć" tests="1" time="0.5">
                    <testcase name="unicode identity" time="not-used-by-importer"/>
                  </testsuite>
                </testsuites>
                """);
        var suite = JUnitXmlTimings.read(List.of(file)).get(0);
        assertEquals("example.Żółć", suite.cases().get(0).className());
        assertEquals(.5, suite.seconds());
        assertThrows(UnsupportedOperationException.class, () -> suite.cases().clear());
        // Deliberate mutation attempt verifies that callers cannot alter coverage evidence.
        //noinspection DataFlowIssue
        assertThrows(UnsupportedOperationException.class, () -> suite.members().clear());
    }

    private Path xml(String relative, String xml) throws Exception {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml);
        return file;
    }
}
