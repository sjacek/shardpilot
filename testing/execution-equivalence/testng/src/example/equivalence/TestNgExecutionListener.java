package example.equivalence;

import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;

/** Fixed primitive fixture parameters and native invocation indices give stable TestNG identities. */
// Registered by name in ExecutionEquivalenceFunctionalTest's useTestNG configuration.
// The native baseline/shard regressions require its evidence and detect missing registration.
@SuppressWarnings("unused")
public final class TestNgExecutionListener implements ITestListener {
    @Override
    public void onTestSuccess(ITestResult result) { record(result, "PASSED"); }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result, result.getThrowable() instanceof AssertionError ? "FAILED" : "ERROR");
    }

    @Override
    public void onTestSkipped(ITestResult result) { record(result, "SKIPPED"); }

    private static synchronized void record(ITestResult result, String outcome) {
        String nativeId = result.getTestClass().getName() + "#"
                + result.getMethod().getConstructorOrMethod().getMethod().toGenericString()
                + "/parameters=" + Arrays.deepToString(result.getParameters())
                + "/invocation=" + result.getMethod().getCurrentInvocationCount();
        String identity = Base64.getUrlEncoder().withoutPadding().encodeToString(nativeId.getBytes(StandardCharsets.UTF_8));
        Path output = Path.of(System.getProperty("execution.evidence"));
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, "v1\ttestng\tTEST\t" + identity + "\t" + outcome + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot record native execution evidence", failure);
        }
    }
}
