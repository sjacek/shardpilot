package io.github.sjacek.shardpilot.maven;

import java.util.List;

/** Native Maven runners retain test execution, discovery, result reporting and JVM ownership. */
public enum TestRunner {
    /** Standard unit-test lifecycle binding; default-test may override its plugin-level configuration. */
    SUREFIRE("maven-surefire-plugin", "test", "unit", "test",
            List.of("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java")),
    /** Integration execution requires both integration-test and verify bindings in the consumer POM. */
    FAILSAFE("maven-failsafe-plugin", "integration-test", "integration", "it.test",
            List.of("**/IT*.java", "**/*IT.java", "**/*ITCase.java"));

    final String artifactId;
    final String goal;
    final String suite;
    final String focusedProperty;
    /** Native naming defaults, applied only when neither inline nor file-based includes are supplied. */
    final List<String> includes;

    TestRunner(String artifactId, String goal, String suite, String focusedProperty, List<String> includes) {
        this.artifactId = artifactId;
        this.goal = goal;
        this.suite = suite;
        this.focusedProperty = focusedProperty;
        this.includes = includes;
    }
}
