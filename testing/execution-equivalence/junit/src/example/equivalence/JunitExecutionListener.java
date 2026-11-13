package example.equivalence;

import org.jspecify.annotations.NullMarked;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/** Records engine identities, including parameter/repetition indices, rather than display names. */
public final class JunitExecutionListener implements TestExecutionListener {
    private TestPlan plan;

    @Override
    @NullMarked
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    @NullMarked
    public void executionSkipped(TestIdentifier identifier, String reason) {
        record(identifier, "SKIPPED");
    }

    @Override
    @NullMarked
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        // Spock data features are CONTAINER_AND_TEST: their iteration events already describe the
        // invocations. Counting the successful aggregate as another execution would inflate totals.
        if (identifier.isTest() && !plan.getChildren(identifier).isEmpty()) return;
        if (!identifier.isTest() && result.getStatus() != TestExecutionResult.Status.FAILED) return;
        String outcome = switch (result.getStatus()) {
            case SUCCESSFUL -> "PASSED";
            case ABORTED -> "ABORTED";
            case FAILED -> result.getThrowable().orElse(null) instanceof AssertionError ? "FAILED" : "ERROR";
        };
        record(identifier, outcome);
    }

    private static synchronized void record(TestIdentifier identifier, String outcome) {
        String identity = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(identifier.getUniqueId().getBytes(StandardCharsets.UTF_8));
        Path output = Path.of(System.getProperty("execution.evidence"));
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, "v1\tjunit-platform\t" + (identifier.isTest() ? "TEST" : "CONTAINER")
                    + "\t" + identity + "\t" + outcome + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot record native execution evidence", failure);
        }
    }
}
