package io.github.sjacek.shardpilot;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.github.sjacek.shardpilot.EvidenceFiles.problem;
import static io.github.sjacek.shardpilot.ExecutionEvidence.Reason.*;

/** Strict execution view of retained Gradle/Surefire/Failsafe XML; timing-import semantics stay unchanged. */
@NullMarked
final class JUnitXmlExecutions {
    private JUnitXmlExecutions() { }

    /** Terminal counts include skips in tests; addition rejects overflow rather than wrapping it. */
    record Counts(long tests, long failures, long errors, long skipped) {
        static Counts zero() { return new Counts(0, 0, 0, 0); }
        Counts plus(Counts value) { return new Counts(Math.addExact(tests, value.tests), Math.addExact(failures, value.failures),
                Math.addExact(errors, value.errors), Math.addExact(skipped, value.skipped)); }
        long executed() { return tests - skipped; }
    }
    /** The display identity is transient validation data and never enters JSON/HTML diagnostics. */
    record Case(String owner, String identity, String outcome) {
        Counts counts() { return new Counts(1, outcome.equals("failed") ? 1 : 0, outcome.equals("error") ? 1 : 0, outcome.equals("skipped") ? 1 : 0); }
    }
    record Suite(String name, List<Case> cases) { }

    /**
     * Parses a hash-verified byte snapshot. remainingCases spans all reports of the node, so
     * splitting XML into many small files cannot evade the invocation limit. Schema-depth checks
     * distinguish actual terminal markers from similarly named tags in captured test output.
     */
    static List<Suite> read(byte[] bytes, ExecutionEvidence.Limits limits, int remainingCases) {
        XMLStreamReader reader = null;
        try {
            reader = JUnitXmlTimings.xmlFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
            var suites = new ArrayList<Suite>();
            var cases = new ArrayList<Case>();
            Counts header = Counts.zero();
            Counts aggregate = null;
            Counts total = Counts.zero();
            String suite = null;
            String owner = null;
            String identity = null;
            String outcome = "passed";
            int depth = 0, suiteDepth = -1, caseDepth = -1, seenCases = 0;
            boolean root = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw problem(INVALID_XML);
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > limits.maxDepth()) throw problem(RESOURCE_LIMIT);
                    if (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty()) throw problem(INVALID_XML);
                    String tag = reader.getLocalName();
                    if (depth == 1) {
                        root = true;
                        if (!tag.equals("testsuite") && !tag.equals("testsuites")) throw problem(INVALID_XML);
                        if (tag.equals("testsuites")) {
                            finiteTime(reader);
                            if (attribute(reader, "tests") != null) aggregate = new Counts(counter(reader, "tests", true),
                                    counter(reader, "failures", false), counter(reader, "errors", false), counter(reader, "skipped", false));
                            else for (String name : List.of("failures", "errors", "skipped")) if (attribute(reader, name) != null) throw problem(INVALID_XML);
                        }
                    }
                    if (tag.equals("testsuite") && (depth == 1 || depth == 2 && suite == null)) {
                        suite = owner(attribute(reader, "name"));
                        suiteDepth = depth;
                        header = new Counts(counter(reader, "tests", true), counter(reader, "failures", false),
                                counter(reader, "errors", false), counter(reader, "skipped", false));
                        finiteTime(reader);
                        cases = new ArrayList<>();
                    } else if (suite != null && depth == suiteDepth + 1 && tag.equals("testsuite")) {
                        throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
                    } else if (suite != null && depth == suiteDepth + 1 && tag.equals("testcase")) {
                        if (++seenCases > remainingCases) throw problem(RESOURCE_LIMIT);
                        String declared = attribute(reader, "classname");
                        owner = owner(declared == null ? suite : declared);
                        identity = attribute(reader, "name");
                        if (identity == null) throw problem(INVALID_XML);
                        // Alternative result/status attribute dialects are not one of the supported
                        // Gradle/Surefire/Failsafe terminal-marker formats. Never treat not-run as passed.
                        if (attribute(reader, "status") != null || attribute(reader, "result") != null) throw problem(INVALID_XML);
                        outcome = "passed";
                        caseDepth = depth;
                        finiteTime(reader);
                    } else if (identity != null && depth == caseDepth + 1) {
                        if (List.of("failure", "error", "skipped").contains(tag)) {
                            if (!outcome.equals("passed")) throw problem(COUNTER_MISMATCH);
                            outcome = tag.equals("failure") ? "failed" : tag;
                        } else if (List.of("flakyFailure", "flakyError", "rerunFailure", "rerunError").contains(tag)) {
                            throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
                        }
                    } else if (depth == 2 && suite == null && !tag.equals("testsuite")) throw problem(INVALID_XML);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (identity != null && depth == caseDepth) {
                        cases.add(new Case(owner, identity, outcome));
                        identity = null;
                    }
                    if (suite != null && depth == suiteDepth) {
                        Counts actual = Counts.zero();
                        for (Case test : cases) actual = actual.plus(test.counts());
                        if (!header.equals(actual)) throw problem(COUNTER_MISMATCH);
                        total = total.plus(actual);
                        suites.add(new Suite(suite, List.copyOf(cases)));
                        suite = null;
                    }
                    depth--;
                }
            }
            if (!root || suites.isEmpty()) throw problem(INVALID_XML);
            // A testsuites summary is a cross-check, never another batch of executions to add.
            if (aggregate != null && !aggregate.equals(total)) throw problem(COUNTER_MISMATCH);
            return List.copyOf(suites);
        } catch (XMLStreamException failure) {
            // Parser messages may quote names, captured output or malformed private data.
            throw problem(INVALID_XML);
        } finally {
            if (reader != null) try { reader.close(); } catch (XMLStreamException ignored) { /* The bounded snapshot was already consumed. */ }
        }
    }

    private static @Nullable String attribute(XMLStreamReader reader, String name) { return reader.getAttributeValue(null, name); }

    private static long counter(XMLStreamReader reader, String name, boolean required) {
        String value = attribute(reader, name);
        if (value == null && !required) return 0;
        if (value == null || !value.matches("[0-9]{1,18}")) throw problem(INVALID_XML);
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw problem(INVALID_XML); }
    }

    private static void finiteTime(XMLStreamReader reader) {
        String time = attribute(reader, "time");
        if (time == null) return;
        try {
            double value = Double.parseDouble(time);
            if (!Double.isFinite(value) || value < 0) throw problem(INVALID_XML);
        } catch (NumberFormatException failure) { throw problem(INVALID_XML); }
    }

    private static String owner(@Nullable String value) {
        if (value == null || value.length() > 4096 || DurationHistory.invalidClassName(value)) throw problem(AMBIGUOUS_EXECUTION_IDENTITY);
        return value;
    }
}
