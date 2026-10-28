package io.github.sjacek.shardpilot;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads timing metadata only. Test output, failure messages and stack traces never enter the model.
 * Suite counters are evidence, not a universal truth about execution: some producers emit zero
 * tests for an executed nested family. Testcase identities/flags supply the necessary fallback,
 * while the producer's suite time remains the sample, including setup costs.
 */
public final class JUnitXmlTimings {
    private JUnitXmlTimings() { }

    /**
     * Metadata of one emitted testcase; time is deliberately absent.
     * @param className binary owner, falling back to the suite name if omitted
     * @param name opaque testcase identity; never logged
     * @param skipped whether a skipped marker was emitted
     * @param failed whether a failure/error marker was emitted
     * @param retried whether a flaky/rerun marker was emitted
     */
    public record TestCase(String className, String name, boolean skipped, boolean failed, boolean retried) { }

    /**
     * Immutable raw suite; duplicate policy is applied later, before classification.
     * @param name binary suite name
     * @param seconds finite nonnegative suite time, including producer-owned setup costs
     * @param tests header test count, zero if absent
     * @param skipped header skipped count, zero if absent
     * @param failures header failure count, zero if absent
     * @param errors header error count, zero if absent
     * @param cases testcase metadata, possibly empty
     * @param source relative input label for safe, relocatable diagnostics
     */
    public record Suite(String name, double seconds, int tests, int skipped, int failures, int errors,
                        List<TestCase> cases, String source) {
        /** Copies testcase evidence so callers cannot mutate a parsed suite. */
        public Suite { cases = List.copyOf(cases); }

        /**
         * Whether testcase metadata, or otherwise header counters, proves execution.
         * @return whether testcase metadata, or otherwise header counters, proves execution
         */
        public boolean executed() {
            return cases.isEmpty() ? tests > 0 && skipped != tests : cases.stream().anyMatch(test -> !test.skipped());
        }

        /**
         * Whether a header or testcase marks a failed execution.
         * @return whether a header or testcase marks a failed execution
         */
        public boolean failed() { return failures > 0 || errors > 0 || cases.stream().anyMatch(TestCase::failed); }

        /**
         * Whether an explicit rerun/flaky marker was emitted.
         * @return whether an explicit rerun/flaky marker was emitted
         */
        public boolean retried() { return cases.stream().anyMatch(TestCase::retried); }

        /**
         * Whether testcase identity repeats without an explicit retry marker.
         * @return whether testcase identity repeats without an explicit retry marker
         */
        public boolean ambiguous() {
            var identities = new HashSet<List<String>>();
            return !retried() && cases.stream().anyMatch(test -> !identities.add(List.of(test.className(), test.name())));
        }

        /**
         * Identifies members covered by the suite, including its enclosing name and executed
         * testcase owners. Skipped testcase owners add no timing coverage. The refresh policy
         * compares these sets between distinct suites to detect inclusive/exclusive overlap.
         * @return members covered by the suite's time, including an inclusive enclosing suite
         */
        public Set<String> members() {
            var members = new HashSet<String>();
            members.add(name);
            cases.stream().filter(test -> !test.skipped()).map(TestCase::className).forEach(members::add);
            return Set.copyOf(members);
        }
    }

    /**
     * Safe diagnostic retaining only fully closed preceding suites, never a partially parsed suite.
     * A parser exception is deliberately not chained: its message or cause can quote XML payloads.
     */
    public static final class ReadFailure extends IOException {
        /** Completed metadata preceding the malformed/unreadable input, never parser payloads. */
        private final List<Suite> partial;
        private ReadFailure(String source, String element, String reason, List<Suite> partial) {
            // Do not retain XMLStreamException: its message/cause can quote untrusted XML content.
            super("ShardPilot XML " + source.replaceAll("[\\p{Cntrl}]", "?") + " <" + element + ">: " + reason);
            this.partial = List.copyOf(partial);
        }
        /**
         * Successfully parsed suites preceding the unreadable/invalid input.
         * @return successfully parsed suites preceding the unreadable/invalid input
         */
        public List<Suite> partial() { return partial; }
    }

    /**
     * Reads TEST-*.xml files recursively; repeated paths are read once and ordering is deterministic.
     * Missing report directories contribute no suites; a refresh of no measurements fails separately.
     * Diagnostic paths are relative to numbered input roots so reports remain relocatable.
     * @param inputs report directories or individual TEST-*.xml files
     * @return immutable raw suites
     * @throws ReadFailure if an input is unreadable, malformed or contains a DTD/entity
     */
    public static List<Suite> read(Collection<Path> inputs) throws ReadFailure {
        var suites = new ArrayList<Suite>();
        var files = new TreeMap<Path, String>();
        var roots = inputs.stream().map(path -> path.toAbsolutePath().normalize()).distinct().sorted().toList();
        for (int index = 0; index < roots.size(); index++) {
            Path root = roots.get(index);
            String label = "input-" + (index + 1) + "/";
            if (!Files.exists(root)) continue;
            try {
                if (Files.isDirectory(root)) {
                    try (var walk = Files.walk(root)) {
                        for (Path file : walk.filter(Files::isRegularFile).filter(JUnitXmlTimings::report).sorted().toList()) {
                            files.putIfAbsent(file, label + root.relativize(file).toString().replace('\\', '/'));
                        }
                    }
                } else if (report(root)) files.putIfAbsent(root, label + root.getFileName());
            } catch (IOException | java.io.UncheckedIOException failure) {
                throw new ReadFailure(label, "reports", "cannot list reports", suites);
            }
        }
        for (var entry : files.entrySet()) parse(entry.getKey(), entry.getValue(), suites);
        return List.copyOf(suites);
    }

    private static boolean report(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml");
    }

    private static void parse(Path path, String source, List<Suite> result) throws ReadFailure {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External entities forbidden"); });
        XMLStreamReader reader = null;
        String element = "document";
        try (var input = Files.newInputStream(path)) {
            reader = factory.createXMLStreamReader(input);
            int depth = 0;
            int suiteDepth = -1;
            int caseDepth = -1;
            SuiteBuilder suite = null;
            CaseBuilder test = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new Invalid("DTD and entities are forbidden");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String tag = reader.getLocalName();
                    if (depth == 1 && !tag.equals("testsuite") && !tag.equals("testsuites")) {
                        throw new Invalid("expected testsuite or testsuites root");
                    }
                    // Only schema-level positions count. Similarly named elements inside output,
                    // properties or failure payloads must not become suites, cases or retry markers.
                    if (tag.equals("testsuite") && (depth == 1 || depth == 2 && suite == null)) {
                        element = "testsuite";
                        suite = new SuiteBuilder(reader, source);
                        suiteDepth = depth;
                    } else if (suite != null && depth == suiteDepth + 1 && tag.equals("testsuite")) {
                        throw new Invalid("nested aggregate suites are unsupported");
                    } else if (suite != null && depth == suiteDepth + 1 && tag.equals("testcase")) {
                        element = "testcase";
                        test = new CaseBuilder(reader, suite.name);
                        caseDepth = depth;
                    } else if (test != null && depth == caseDepth + 1) {
                        switch (tag) {
                            case "skipped" -> test.skipped = true;
                            case "failure", "error" -> test.failed = true;
                            case "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> test.retried = true;
                            default -> { /* Neither output nor diagnostic payload is inspected. */ }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (test != null && depth == caseDepth) {
                        // Testcases are created only within an active suite; assert that parser invariant.
                        java.util.Objects.requireNonNull(suite, "An active testcase requires an active suite").cases.add(test.build());
                        test = null;
                        element = "testsuite";
                    }
                    if (suite != null && depth == suiteDepth) {
                        result.add(suite.build());
                        suite = null;
                        element = "document";
                    }
                    depth--;
                }
            }
        } catch (IOException | XMLStreamException | Invalid failure) {
            throw new ReadFailure(source, element, failure instanceof Invalid ? failure.getMessage() : "cannot read valid XML", result);
        } finally {
            if (reader != null) try { reader.close(); } catch (XMLStreamException ignored) { /* Already consumed or failed safely. */ }
        }
    }

    private static String attribute(XMLStreamReader reader, String name) { return reader.getAttributeValue(null, name); }

    private static int counter(XMLStreamReader reader, String name) throws Invalid {
        String value = attribute(reader, name);
        if (value == null) return 0;
        try {
            int number = Integer.parseInt(value);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException failure) { throw new Invalid("invalid nonnegative counter"); }
    }

    private static String className(String name) throws Invalid {
        if (DurationHistory.invalidClassName(name)) throw new Invalid("invalid binary class name");
        return name;
    }

    private static final class SuiteBuilder {
        final String name;
        final String source;
        final double seconds;
        final int tests, skipped, failures, errors;
        final List<TestCase> cases = new ArrayList<>();
        SuiteBuilder(XMLStreamReader reader, String source) throws Invalid {
            this.source = source;
            name = className(attribute(reader, "name"));
            try {
                seconds = Double.parseDouble(attribute(reader, "time"));
                if (!Double.isFinite(seconds) || seconds < 0) throw new NumberFormatException();
            } catch (NumberFormatException | NullPointerException failure) { throw new Invalid("invalid finite nonnegative time"); }
            tests = counter(reader, "tests"); skipped = counter(reader, "skipped");
            failures = counter(reader, "failures"); errors = counter(reader, "errors");
        }
        Suite build() { return new Suite(name, seconds, tests, skipped, failures, errors, cases, source); }
    }

    private static final class CaseBuilder {
        final String className, name;
        boolean skipped, failed, retried;
        CaseBuilder(XMLStreamReader reader, String fallback) throws Invalid {
            String declared = attribute(reader, "classname");
            className = className(declared == null ? fallback : declared);
            name = attribute(reader, "name");
            if (name == null) throw new Invalid("missing testcase identity");
        }
        TestCase build() { return new TestCase(className, name, skipped, failed, retried); }
    }

    private static final class Invalid extends Exception {
        Invalid(String message) { super(message); }
    }
}
