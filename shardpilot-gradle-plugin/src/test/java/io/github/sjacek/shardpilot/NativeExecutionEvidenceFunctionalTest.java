package io.github.sjacek.shardpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Native engines produce the XML and hashed invocation IDs; the harness observes the real build result. */
class NativeExecutionEvidenceFunctionalTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"junit", "spock", "testng"})
    void validatesRealNativeXmlIncludingRepeatedSpockAndTestNgDisplayNames(String framework) throws Exception {
        fixture(framework);
        Instant started = Instant.now();
        var result = VerifiedTestKit.create(directory).withPluginClasspath()
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments("test", "--offline", "--console=plain", "--stacktrace").build();
        Instant finished = Instant.now();
        var nativeTask = result.task(":test"); assertNotNull(nativeTask); assertEquals(TaskOutcome.SUCCESS, nativeTask.getOutcome());
        Path node = directory.resolve("nodes/node-1"); Files.createDirectories(node.resolve("reports"));
        var reports = new ArrayList<Map<String, Object>>();
        try (var paths = Files.list(directory.resolve("build/test-results/test"))) {
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                Path target = node.resolve("reports").resolve(file.getFileName()); Files.copy(file, target);
                reports.add(descriptor("reports/" + file.getFileName(), target));
            }
        }
        Files.copy(directory.resolve("build/native-ledger.json"), node.resolve("invocations.json"));
        Path baseline = directory.resolve("baseline.yaml"), selection = directory.resolve("selection.json"), expected = directory.resolve("expected.json");
        ClassManifest.write(baseline, Set.of("example.AlphaTest")); ClassManifest.write(node.resolve("plan.yaml"), Set.of("example.AlphaTest"));
        Files.writeString(selection, "{\"mode\":\"full\",\"schemaVersion\":1}");
        var cohort = Map.of("revision", "a".repeat(40), "runId", "native-testdata", "attempt", 1, "project", "generic-example", "suite", framework, "tool", "gradle", "runner", "gradle-test");
        var selectionId = Map.of("mode", "full", "sha256", hash(selection), "eligibilitySha256", hash(baseline));
        var producerSelection = new java.util.TreeMap<String, Object>(selectionId); producerSelection.put("planSha256", hash(node.resolve("plan.yaml")));
        var evidence = Map.of("schemaVersion", 1, "cohort", cohort, "producer", Map.of("jobId", "test-job", "toolVersion", "native-testdata", "adapterVersion", "development"),
                "shard", Map.of("index", 1, "count", 1), "selection", producerSelection,
                "process", Map.of("state", "completed", "exitCode", 0, "startedAt", started.toString(), "finishedAt", finished.toString()),
                "reports", reports, "observed", Map.of("tests", 3, "failures", 0, "errors", 0, "skipped", 0),
                "invocations", descriptor("invocations.json", node.resolve("invocations.json")));
        Files.writeString(node.resolve("evidence.json"), EvidenceJson.write(evidence));
        Files.writeString(expected, EvidenceJson.write(Map.of("schemaVersion", 1, "cohort", cohort, "selection", selectionId,
                "shardCount", 1, "expectedNodes", List.of(Map.of("index", 1, "jobId", "test-job", "planSha256", hash(node.resolve("plan.yaml")),
                        "jobStartedAt", started.toString(), "jobFinishedAt", finished.toString())),
                "skipPolicy", "reject", "allowEmptyShards", false, "minimumExecutedTests", 3, "maximumSkippedTests", 0)));
        var inputs = new ExecutionEvidence.Inputs(expected, baseline, selection, directory.resolve("nodes"), ExecutionEvidence.Limits.defaults());
        Path retained = Path.of("shardpilot-gradle-plugin/build/reports/execution-evidence", framework);
        var evaluation = ExecutionEvidence.writeReports(inputs, retained.resolve("report.json"), retained.resolve("report.html"));
        assertTrue(evaluation.passed(), framework + ": " + evaluation.json());
        assertTrue(evaluation.json().contains("\"tests\":3,\"executed\":3"));
        // Spock deliberately repeats display names; Gradle's TestNG dialect adds row suffixes.
        // Neither reader creates invocation identities from XML row offsets.
        if (framework.equals("spock")) {
            var without = new java.util.TreeMap<>(evidence); without.remove("invocations");
            Files.writeString(node.resolve("evidence.json"), EvidenceJson.write(without));
            assertTrue(ExecutionEvidence.writeReports(inputs, retained.resolve("without-ledger.json"), retained.resolve("without-ledger.html"))
                    .json().contains("AMBIGUOUS_EXECUTION_IDENTITY"));
            Files.writeString(node.resolve("evidence.json"), EvidenceJson.write(evidence));
        }
        bindFrozenSelection(framework, baseline, selection, expected, node, retained);
    }

    /** Reuses the actual framework XML/ledger, keeping v1 request/node documents and v1 golden behavior. */
    private void bindFrozenSelection(String suite, Path baseline, Path selection, Path expected, Path node, Path retained) throws Exception {
        var policy = SelectionPolicyYaml.parse("schemaVersion: 1\nprofiles: {smoke-testdata: {mode: smoke, mandatory: {classes: [example.AlphaTest]}}}\n");
        Path policyFile = directory.resolve("policy.yaml"); Files.writeString(policyFile, SelectionPolicyYaml.format(policy));
        var eligible = ClassManifest.read(baseline, false); var git = GitChanges.notRequired();
        ProfileSelection.prepare(directory.resolve("snapshot"), policy,
                new SelectionContext("smoke-testdata", eligible, git.changes(), Map.of(), Set.of()), git, suite, "", 1);
        var compiled = new CompiledArtifacts.Inputs(directory, "a".repeat(40), suite,
                Files.isDirectory(directory.resolve("build/resources")) ? List.of("build/classes", "build/resources") : List.of("build/classes"),
                List.of("build/classes"), List.of(), CompiledArtifacts.Limits.defaults());
        Path manifest = directory.resolve("compiled-manifest.json"); CompiledArtifacts.seal(compiled, manifest);
        var binding = new ExecutionSelection.Compiled(compiled, manifest, "");
        ExecutionSelection.write(new ExecutionSelection.Inputs(directory.resolve("snapshot"), policyFile, baseline, null, null,
                "smoke-testdata", "a".repeat(40), suite, 1, binding), selection);
        // Only the canonical-selection mode/hash changes: no new producer fields are needed.
        for (Path file : List.of(expected, node.resolve("evidence.json"))) {
            var document = object(EvidenceJson.parse(Files.readString(file)));
            var selected = object(document.get("selection"));
            selected.put("mode", "explicit"); selected.put("sha256", hash(selection));
            document.put("selection", selected);
            Files.writeString(file, EvidenceJson.write(document));
        }
        var inputs = new ExecutionEvidence.Inputs(expected, baseline, selection, directory.resolve("nodes"), ExecutionEvidence.Limits.defaults(), binding);
        var bound = ExecutionEvidence.writeReports(inputs, retained.resolve("bound-report.json"), retained.resolve("bound-report.html"));
        assertTrue(bound.passed(), bound.json()); assertTrue(bound.json().contains("\"selectionProvenance\""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@org.jspecify.annotations.Nullable Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private void fixture(String framework) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='c2-native'\n");
        Files.writeString(directory.resolve("gradle.properties"), "org.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx256m\n");
        String dependencies = switch (framework) {
            case "junit" -> "testImplementation platform('org.junit:junit-bom:6.1.3'); testImplementation 'org.junit.jupiter:junit-jupiter'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher'";
            case "spock" -> "testImplementation 'org.spockframework:spock-core:2.5-groovy-5.0'; testImplementation 'org.apache.groovy:groovy:5.1.3'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher:6.1.3'";
            default -> "testImplementation 'org.testng:testng:7.12.0'";
        };
        if (!framework.equals("testng")) dependencies += "; testImplementation 'org.junit.platform:junit-platform-launcher:6.1.3'";
        Files.writeString(directory.resolve("build.gradle"), "plugins { id '" + (framework.equals("spock") ? "groovy" : "java") + "'; id 'io.github.sjacek.shardpilot' }\n"
                + "repositories { mavenCentral() }\ndependencies { " + dependencies + " }\ntest { maxHeapSize='192m'; "
                + (framework.equals("testng") ? "useTestNG { listeners << 'example.NativeLedger' }" : "useJUnitPlatform()")
                + "; systemProperty 'c2.ledger', layout.buildDirectory.file('native-ledger.json').get().asFile.absolutePath }\n");
        String source = switch (framework) {
            case "junit" -> "package example; import org.junit.jupiter.api.*; public class AlphaTest { @Test void normal() {} @RepeatedTest(2) void repeated() {} }";
            case "spock" -> "package example\nimport spock.lang.*\nclass AlphaTest extends Specification { def 'normal'() { expect: true }; @Unroll('same') def 'rows'() { expect: row > 0; where: row << [1,2] } }";
            default -> "package example; import org.testng.annotations.*; public class AlphaTest { @Test public void normal() {} @DataProvider(name=\"rows\") public Object[][] rows() { return new Object[][]{{1},{2}}; } @Test(dataProvider=\"rows\") public void repeated(int row) { if(row<1) throw new AssertionError(); } }";
        };
        Path sources = directory.resolve(framework.equals("spock") ? "src/test/groovy/example" : "src/test/java/example");
        Files.createDirectories(sources); Files.writeString(sources.resolve(framework.equals("spock") ? "AlphaTest.groovy" : "AlphaTest.java"), source);
        Path java = directory.resolve("src/test/java/example"); Files.createDirectories(java);
        Files.writeString(java.resolve("NativeLedger.java"), listener(framework.equals("testng")));
        if (!framework.equals("testng")) {
            Path registration = directory.resolve("src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener");
            Files.createDirectories(registration.getParent()); Files.writeString(registration, "example.NativeLedger\n");
        }
    }

    /** Test-only native listeners hash engine identities in memory; no method/parameter payload is retained. */
    private static String listener(boolean testng) {
        String callbacks = testng ? """
                implements org.testng.ITestListener {
                  public void onTestSuccess(org.testng.ITestResult r) { add(r.getTestClass().getName(), r.getMethod().getMethodName()+"/"+r.getMethod().getCurrentInvocationCount(), "passed"); }
                  public void onTestFailure(org.testng.ITestResult r) { throw new AssertionError("Unexpected fixture failure"); }
                  public void onFinish(org.testng.ITestContext c) { finish(); }
                """ : """
                implements org.junit.platform.launcher.TestExecutionListener {
                  private org.junit.platform.launcher.TestPlan plan;
                  public void testPlanExecutionStarted(org.junit.platform.launcher.TestPlan p) { plan=p; }
                  public void executionFinished(org.junit.platform.launcher.TestIdentifier t, org.junit.platform.engine.TestExecutionResult r) {
                    if (!t.isTest() || !plan.getChildren(t).isEmpty()) return;
                    if (r.getStatus()!=org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL) throw new AssertionError("Unexpected fixture failure");
                    org.junit.platform.launcher.TestIdentifier owner=t;
                    while (!(owner.getSource().orElse(null) instanceof org.junit.platform.engine.support.descriptor.MethodSource)) owner=plan.getParent(owner).orElseThrow();
                    String name=((org.junit.platform.engine.support.descriptor.MethodSource)owner.getSource().orElseThrow()).getClassName();
                    add(name,t.getUniqueId(),"passed");
                  }
                  public void testPlanExecutionFinished(org.junit.platform.launcher.TestPlan p) { finish(); }
                """;
        return "package example; public final class NativeLedger " + callbacks + """
                  private static final java.util.List<String> rows=new java.util.ArrayList<>();
                  private static synchronized void add(String owner,String nativeId,String outcome) {
                    try {
                      String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nativeId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                      rows.add("{\\"id\\":\\""+hash+"\\",\\"className\\":\\""+owner+"\\",\\"outcome\\":\\""+outcome+"\\"}");
                    } catch(Exception e) { throw new AssertionError("Cannot retain fixture ledger",e); }
                  }
                  private static synchronized void finish() {
                    try {
                      java.nio.file.Path path=java.nio.file.Path.of(System.getProperty("c2.ledger"));
                      java.nio.file.Files.createDirectories(path.getParent());
                      java.nio.file.Files.writeString(path,"{\\"schemaVersion\\":1,\\"runner\\":\\"RUNNER\\",\\"invocations\\":["+String.join(",",rows)+"]}");
                    } catch(Exception e) { throw new AssertionError("Cannot retain fixture ledger",e); }
                  }
                }
                """.replace("RUNNER", testng ? "testng" : "junit-platform");
    }

    private static String hash(Path file) throws Exception { return EvidenceFiles.digest(Files.readAllBytes(file)); }
    private static Map<String, Object> descriptor(String name, Path file) throws Exception { return Map.of("path", name, "sha256", hash(file), "bytes", Files.size(file)); }
}
