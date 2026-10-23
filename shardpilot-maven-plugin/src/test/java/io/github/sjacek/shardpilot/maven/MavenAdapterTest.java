package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ClassManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Model-level edge cases complement Invoker's real native-runner builds. No mocked Maven APIs. */
class MavenAdapterTest {
    @TempDir Path directory;
    SelectMojo select;
    Plugin nativePlugin;
    Xpp3Dom configuration;

    @BeforeEach
    void fixture() throws IOException {
        select = context(new SelectMojo());
        nativePlugin = new Plugin();
        nativePlugin.setGroupId("org.apache.maven.plugins");
        nativePlugin.setArtifactId("maven-surefire-plugin");
        configuration = new Xpp3Dom("configuration");
        add(configuration, "excludesFile", "${project.build.directory}/shardpilot/unit/excludes.txt");
        nativePlugin.setConfiguration(configuration);
        select.project.getBuild().addPlugin(nativePlugin);
        for (String name : List.of("OneTest", "TwoTest", "Helper", "OneTest$Nested")) {
            Path file = Path.of(select.project.getBuild().getTestOutputDirectory(), "example", name + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
    }

    @Test
    void defaultsPreserveTopLevelRunnerEligibilityAndGeneratedFilesAreNotReadBack() throws Exception {
        select.execute();
        assertEquals(Set.of("example.OneTest", "example.TwoTest"),
                ClassManifest.read(select.selectionDirectory().resolve("actual-1.yaml"), false));
        Files.writeString(select.exclusions(), "**/*\n");
        assertEquals(Set.of("example.OneTest", "example.TwoTest"), new RunnerConfiguration(select).scan().eligible());
        assertTrue(Files.readString(select.exclusions()).contains("**/*"));
    }

    @Test
    void executionOverridesIncludesAndExistingFileExclusionsArePreserved() throws Exception {
        var includes = new Xpp3Dom("includes");
        add(includes, "include", "**/*Test.class");
        configuration.addChild(includes);
        var execution = new PluginExecution();
        execution.setId("default-test"); // Lifecycle-bound overrides need not repeat the default goal.
        var overrides = new Xpp3Dom("configuration");
        var excludes = new Xpp3Dom("excludes");
        add(excludes, "exclude", "**/OneTest.class");
        overrides.addChild(excludes);
        execution.setConfiguration(overrides);
        nativePlugin.addExecution(execution);
        select.additionalExcludesFile = directory.resolve("business-excludes.txt").toFile();
        Files.writeString(select.additionalExcludesFile.toPath(), "# existing metadata\n\n**/TwoTest.class\n");
        assertTrue(new RunnerConfiguration(select).scan().eligible().isEmpty());
        select.execute();
        assertTrue(ClassManifest.read(select.selectionDirectory().resolve("actual-1.yaml"), true).isEmpty());
        assertTrue(Files.readString(select.exclusions()).contains("**/*"));
    }

    @Test
    void relativeIncludeFilesAndRegexPatternsRespectMaintainedExclusions() throws Exception {
        Path includes = directory.resolve("native-includes.txt");
        // Surefire's regex matcher uses host separators; accept both Windows and Unix paths.
        Files.writeString(includes, "# Native patterns, with CRLF and blank records\r\n\r\n%regex[example[/\\\\](One|Two)Test.class]\r\n");
        add(configuration, "includesFile", "${native.includes}");
        select.project.getProperties().setProperty("native.includes", "native-includes.txt");
        select.additionalExcludesFile = directory.resolve("maintained-excludes.txt").toFile();
        Files.writeString(select.additionalExcludesFile.toPath(), "# Existing application rule\r\n**/TwoTest.class\r\n");
        select.execute();
        assertEquals(Set.of("example.OneTest"), ClassManifest.read(select.selectionDirectory().resolve("actual-1.yaml"), false));
        assertTrue(Files.readString(select.exclusions()).contains("example/TwoTest.class"));
        assertTrue(Files.readString(select.exclusions()).contains("example/Helper.class"));
    }

    @Test
    void executionPatternOverridesAreEvaluatedWithoutMutatingTheEffectiveModel() throws Exception {
        var includes = new Xpp3Dom("includes");
        add(includes, "include", "**/*Test.class");
        configuration.addChild(includes);
        var execution = new PluginExecution();
        execution.setId("default-test");
        var overrides = new Xpp3Dom("configuration");
        var executionIncludes = new Xpp3Dom("includes");
        add(executionIncludes, "include", "${test.pattern}");
        overrides.addChild(executionIncludes);
        execution.setConfiguration(overrides);
        nativePlugin.addExecution(execution);
        String beforeBase = configuration.toString();
        String beforeExecution = overrides.toString();
        select.project.getProperties().setProperty("test.pattern", "**/OneTest.class");
        assertEquals(Set.of("example.OneTest"), new RunnerConfiguration(select).scan().eligible());
        select.project.getProperties().setProperty("test.pattern", "**/TwoTest.class");
        assertEquals(Set.of("example.TwoTest"), new RunnerConfiguration(select).scan().eligible());
        assertEquals(beforeBase, configuration.toString());
        assertEquals(beforeExecution, overrides.toString(), "expression evaluation must not freeze the effective model");
    }

    @ParameterizedTest
    @ValueSource(strings = {"includesFile", "additionalExcludesFile"})
    void missingExplicitPatternFilesFailBeforeWritingSelection(String input) {
        if (input.equals("includesFile")) add(configuration, "includesFile", "missing-includes.txt");
        else select.additionalExcludesFile = directory.resolve("missing-excludes.txt").toFile();
        assertThrows(MojoExecutionException.class, select::execute);
        assertFalse(Files.exists(select.exclusions()));
        assertFalse(Files.exists(select.selectionDirectory()));
    }

    @Test
    void unresolvedNativeExpressionsCannotPublishActualEvidence() {
        add(configuration, "includesFile", "${not.configured}");
        assertThrows(MojoFailureException.class, select::execute);
        assertFalse(Files.exists(select.namespace()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "skipTests", "maven.test.skip"})
    void focusedOrSkippedRunsCannotPublishMisleadingActualEvidence(String property) {
        select.session.getUserProperties().setProperty(property, "true");
        assertThrows(MojoFailureException.class, select::execute);
        assertFalse(Files.exists(select.selectionDirectory()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"surefire.includes", "surefire.excludes", "surefire.includesFile", "surefire.excludesFile", "surefire.testClassesDirectory"})
    void overridingNativeFilePropertiesCannotBypassTheRecordedSelection(String property) {
        select.session.getUserProperties().setProperty(property, "untracked.txt");
        assertThrows(MojoFailureException.class, select::execute);
    }

    @ParameterizedTest
    @ValueSource(strings = {"it.test", "skipITs", "failsafe.includes", "failsafe.excludes", "failsafe.includesFile",
            "failsafe.excludesFile", "failsafe.testClassesDirectory"})
    void failsafeShortcutsCannotBypassIntegrationSelection(String property) {
        configureFailsafe();
        select.session.getUserProperties().setProperty(property, "true");
        assertThrows(MojoFailureException.class, select::execute);
        assertFalse(Files.exists(select.namespace()));
    }

    @Test
    void failsafeDefaultsSelectOnlyIntegrationNamingFamilies() throws Exception {
        configureFailsafe();
        Path compiled = Path.of(select.project.getBuild().getTestOutputDirectory(), "example");
        for (String name : List.of("FirstIT", "ITSecond", "ThirdITCase", "FirstIT$Nested")) {
            Files.write(compiled.resolve(name + ".class"), new byte[0]);
        }
        select.execute();
        assertEquals(Set.of("example.FirstIT", "example.ITSecond", "example.ThirdITCase"),
                ClassManifest.read(select.selectionDirectory().resolve("actual-1.yaml"), false));
        assertEquals(directory.resolve("target/shardpilot/integration"), select.namespace());
        assertFalse(Files.exists(directory.resolve("target/shardpilot/unit")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "system"})
    void bypassesAreRejectedRegardlessOfTheirPropertyOrigin(String origin) {
        Properties properties = origin.equals("project") ? select.project.getProperties() : select.session.getSystemProperties();
        properties.setProperty("surefire.excludesFile", "untracked.txt");
        assertThrows(MojoFailureException.class, select::execute);
        assertFalse(Files.exists(select.selectionDirectory()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dependenciesToScan", "suiteXmlFiles"})
    void externalDiscoverySourcesCannotBeRepresentedAsLocalCandidateCoverage(String setting) {
        var unsupported = new Xpp3Dom(setting);
        add(unsupported, "item", "external-tests");
        configuration.addChild(unsupported);
        assertThrows(MojoFailureException.class, select::execute);
        assertFalse(Files.exists(select.namespace()));
    }

    @Test
    void compiledPackageAndModuleMetadataAreNotClassIdentities() throws Exception {
        for (String metadata : List.of("module-info.class", "example/package-info.class")) {
            Files.write(Path.of(select.project.getBuild().getTestOutputDirectory()).resolve(metadata), new byte[0]);
        }
        select.execute();
        assertEquals(2, ClassManifest.read(select.selectionDirectory().resolve("actual-1.yaml"), false).size());
        assertTrue(Files.readString(select.exclusions()).contains("**/module-info.class"));
    }

    @Test
    void nativeMethodPatternsAndMultipleRunnerExecutionsAreRejected() {
        var includes = new Xpp3Dom("includes");
        add(includes, "include", "example.OneTest#method");
        configuration.addChild(includes);
        assertThrows(IllegalArgumentException.class, () -> new RunnerConfiguration(select).scan());
        for (String id : List.of("first", "second")) {
            var execution = new PluginExecution();
            execution.setId(id);
            execution.addGoal("test");
            nativePlugin.addExecution(execution);
        }
        assertThrows(IllegalArgumentException.class, () -> new RunnerConfiguration(select));
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom", "disabled", "unbound-failsafe"})
    void implicitOrDisabledNativeExecutionsCannotInvalidateRecordedOwnership(String configurationType) {
        if (configurationType.equals("unbound-failsafe")) {
            select.runner = TestRunner.FAILSAFE;
            nativePlugin.setArtifactId("maven-failsafe-plugin");
        } else {
            var execution = new PluginExecution();
            execution.setId(configurationType.equals("custom") ? "extra-tests" : "default-test");
            execution.addGoal("test");
            if (configurationType.equals("disabled")) execution.setPhase("none");
            nativePlugin.addExecution(execution);
        }
        assertThrows(IllegalArgumentException.class, () -> new RunnerConfiguration(select));
    }

    @Test
    void unwiredRunnerCannotSilentlyRunAllTestsAndInvalidHistoryPreservesEarlierSelection() throws Exception {
        select.execute();
        String original = Files.readString(select.exclusions());
        select.durationsFile = directory.resolve("durations.tsv").toFile();
        Files.writeString(select.durationsFile.toPath(), "example.OneTest\tNaN\n");
        assertThrows(MojoFailureException.class, select::execute);
        assertEquals(original, Files.readString(select.exclusions()));
        configuration.getChild("excludesFile").setValue("other.txt");
        assertThrows(IllegalArgumentException.class, () -> new RunnerConfiguration(select).scan());
    }

    @Test
    void customNamespaceUsesTheSamePlanAndSelectionAndFailedPreparationPreservesTheCohort() throws Exception {
        select.suite = "privacy-catalog";
        select.directory = directory.resolve("custom evidence").toFile();
        configuration.getChild("excludesFile").setValue(select.exclusions().toString());
        PrepareMojo prepare = selectionContext(new PrepareMojo());
        prepare.execute();
        assertFalse(Files.exists(select.selectionDirectory()), "preparation must not impersonate native selection");
        select.execute();
        Path planned = select.planDirectory().resolve("planned-1.yaml");
        Path actual = select.selectionDirectory().resolve("actual-1.yaml");
        assertEquals(Files.readString(planned), Files.readString(actual));
        List<Path> artifacts = List.of(select.planDirectory().resolve("baseline.yaml"), planned, actual, select.exclusions());
        List<String> before = artifacts.stream().map(MavenAdapterTest::read).toList();
        prepare.durationsFile = directory.resolve("invalid-history.tsv").toFile();
        Files.writeString(prepare.durationsFile.toPath(), "example.OneTest\tNaN\n");
        prepare.shardCount = 2;
        assertThrows(MojoFailureException.class, prepare::execute);
        assertEquals(before, artifacts.stream().map(MavenAdapterTest::read).toList());
        assertFalse(Files.exists(select.planDirectory().resolve("planned-2.yaml")));
    }

    @Test
    void exclusionsWriteFailureCannotCreateActualEvidence() throws IOException {
        Files.createDirectories(select.exclusions());
        assertThrows(MojoExecutionException.class, select::execute);
        assertFalse(Files.exists(select.selectionDirectory()));
    }

    @Test
    void pomAggregatorsSkipWithoutCreatingArtifactsAndSuitesCannotEscapeTheirNamespace() throws Exception {
        select.project.setPackaging("pom");
        select.execute();
        assertFalse(Files.exists(select.namespace()));
        select.project.setPackaging("jar");
        select.suite = "../outside";
        assertThrows(MojoFailureException.class, select::execute);
    }

    @Test
    void standaloneVerificationWritesFailureReportsWithoutInspectingRunnerOrCompiling() throws Exception {
        VerifyMojo verify = context(new VerifyMojo());
        assertThrows(MojoFailureException.class, verify::execute);
        assertTrue(Files.readString(verify.namespace().resolve("coverage.json")).contains("baseline.yaml"));
        assertTrue(Files.readString(verify.namespace().resolve("coverage.html")).contains("Verification: FAILED"));
        assertFalse(Files.exists(verify.planDirectory()));
    }

    @Test
    void verificationReportIoFailureIsAnExecutionErrorRatherThanAValidationResult() throws IOException {
        VerifyMojo verify = context(new VerifyMojo());
        Files.createDirectories(verify.namespace().resolve("coverage.json"));
        assertThrows(MojoExecutionException.class, verify::execute);
        assertFalse(Files.exists(verify.namespace().resolve("coverage.html")));
    }

    @Test
    void allGoalsSkipPomOnlyAggregatorsWithoutResolvingRunnerOrCreatingArtifacts() throws Exception {
        for (AbstractShardMojo goal : List.of(context(new PrepareMojo()), context(new SelectMojo()), context(new VerifyMojo()))) {
            goal.project.setPackaging("pom");
            goal.execute();
            assertFalse(Files.exists(goal.namespace()));
        }
    }

    /** Builds a valid Failsafe boundary so rejection tests exercise the requested bypass, not missing wiring. */
    private void configureFailsafe() {
        select.runner = TestRunner.FAILSAFE;
        nativePlugin.setArtifactId("maven-failsafe-plugin");
        configuration.getChild("excludesFile").setValue("${project.build.directory}/shardpilot/integration/excludes.txt");
        var execution = new PluginExecution();
        execution.setId("integration-tests");
        execution.addGoal("integration-test");
        execution.addGoal("verify");
        nativePlugin.addExecution(execution);
    }

    /** Reuses one consumer's model while keeping each goal invocation's own managed parameters. */
    private <T extends AbstractShardMojo> T selectionContext(T mojo) {
        mojo.project = select.project;
        mojo.session = select.session;
        mojo.mojoExecution = select.mojoExecution;
        mojo.runner = select.runner;
        mojo.suite = select.suite;
        mojo.directory = select.directory;
        return mojo;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private <T extends AbstractShardMojo> T context(T mojo) {
        var project = new MavenProject();
        project.setFile(directory.resolve("pom.xml").toFile());
        project.setPackaging("jar");
        var build = new Build();
        build.setDirectory(directory.resolve("target").toString());
        build.setTestOutputDirectory(directory.resolve("target/test-classes").toString());
        project.getModel().setBuild(build);
        var request = new DefaultMavenExecutionRequest();
        request.setUserProperties(new Properties());
        request.setSystemProperties(new Properties());
        var session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        session.setCurrentProject(project);
        session.setProjects(List.of(project));
        var descriptor = new MojoDescriptor();
        descriptor.setPluginDescriptor(new PluginDescriptor());
        mojo.project = project;
        mojo.session = session;
        mojo.mojoExecution = new MojoExecution(descriptor);
        return mojo;
    }

    private static void add(Xpp3Dom parent, String name, String value) {
        var child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
