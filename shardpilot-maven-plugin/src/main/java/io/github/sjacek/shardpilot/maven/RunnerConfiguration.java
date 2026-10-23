package io.github.sjacek.shardpilot.maven;

import io.github.sjacek.shardpilot.ClassManifest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.surefire.api.testset.TestListResolver;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Reads one effective runner execution without changing its model. Uses Surefire's own class-pattern
 * resolver; generated exclusions from previous nodes are never read back as eligibility inputs.
 * This is a candidate-file boundary, not a model of engine tags, method discovery or test outcomes.
 * Configuration is copied before merging so scanning a node cannot alter a later reactor execution.
 */
final class RunnerConfiguration {
    private final AbstractShardMojo mojo;
    private final Xpp3Dom configuration;
    private final PluginParameterExpressionEvaluator expressions;

    /** Resolves the single supported native binding, including Surefire's implicit default-test. */
    RunnerConfiguration(AbstractShardMojo mojo) {
        this.mojo = mojo;
        Plugin plugin = mojo.project.getPlugin("org.apache.maven.plugins:" + mojo.runner.artifactId);
        if (plugin == null) throw new IllegalArgumentException("Configure " + mojo.runner.artifactId + " and its excludesFile explicitly");
        var executions = plugin.getExecutions().stream().filter(execution -> execution.getGoals().contains(mojo.runner.goal)
                || mojo.runner == TestRunner.SUREFIRE && "default-test".equals(execution.getId())).toList();
        if (executions.size() > 1) throw new IllegalArgumentException("ShardPilot supports one " + mojo.runner.goal + " execution per project");
        if (mojo.runner == TestRunner.SUREFIRE && !executions.isEmpty()
                && !"default-test".equals(executions.get(0).getId())) {
            throw new IllegalArgumentException("Configure Surefire's default-test execution; an additional binding would also run the implicit default");
        }
        if (mojo.runner == TestRunner.FAILSAFE && (executions.isEmpty() || plugin.getExecutions().stream()
                .noneMatch(execution -> execution.getGoals().contains("verify") && !"none".equals(execution.getPhase())))) {
            throw new IllegalArgumentException("Bind Failsafe integration-test and verify goals explicitly");
        }
        if (!executions.isEmpty() && "none".equals(executions.get(0).getPhase())) {
            throw new IllegalArgumentException("ShardPilot cannot record selection for a disabled runner execution");
        }
        // Execution values dominate plugin defaults; clone both trees to keep Maven's effective model intact.
        var base = plugin.getConfiguration() instanceof Xpp3Dom xml ? new Xpp3Dom(xml) : new Xpp3Dom("configuration");
        configuration = executions.isEmpty() || !(executions.get(0).getConfiguration() instanceof Xpp3Dom xml)
                ? base : Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(xml), base);
        expressions = new PluginParameterExpressionEvaluator(mojo.session, mojo.mojoExecution);
    }

    /**
     * Scans compiled paths without loading classes. Validate bypasses and native output wiring
     * before returning eligibility; callers may then write selection artifacts without changing
     * which classes the native runner is allowed to consider.
     */
    Selection scan() throws IOException {
        rejectBypass();
        String output = scalar("testClassesDirectory", null);
        Path root = output == null ? Path.of(mojo.project.getBuild().getTestOutputDirectory()) : resolve(output);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Compiled test directory is missing; run test-compile before ShardPilot: " + root);
        String wired = scalar("excludesFile", null);
        if (wired == null || !resolve(wired).toAbsolutePath().normalize().equals(mojo.exclusions().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Configure " + mojo.runner.artifactId + ".excludesFile to " + mojo.exclusions());
        }
        List<String> includes = patterns("includes");
        String includesFile = scalar("includesFile", null);
        if (includesFile != null) includes.addAll(readPatterns(resolve(includesFile)));
        if (includes.isEmpty()) includes.addAll(mojo.runner.includes);
        List<String> excludes = patterns("excludes");
        // Mirrors the runner's default inner-class candidate exclusion; Jupiter still discovers nested tests via their owner.
        if (excludes.isEmpty()) excludes.add("**/*$*");
        // Only maintained exclusions are input. Reading the generated excludesFile here would
        // recursively narrow later nodes to the previous node's selection.
        if (mojo.additionalExcludesFile != null) excludes.addAll(readPatterns(mojo.additionalExcludesFile.toPath()));
        var filter = new TestListResolver(includes, excludes);
        if (filter.hasMethodPatterns()) throw new IllegalArgumentException("ShardPilot eligibility accepts class patterns only, not method filters");
        List<File> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !Set.of("module-info.class", "package-info.class").contains(path.getFileName().toString()))
                    .map(Path::toFile).toList();
        }
        Set<String> all = ClassManifest.compiled(files, List.of(root.toFile()));
        var eligible = new TreeSet<String>();
        for (String name : all) if (filter.shouldRun(classFile(name), null)) eligible.add(name);
        return new Selection(all, eligible);
    }

    /** Rejects runner shortcuts that could make recorded candidates differ from native execution. */
    private void rejectBypass() {
        String nativePrefix = mojo.runner == TestRunner.SUREFIRE ? "surefire." : "failsafe.";
        for (String override : List.of("includes", "excludes", "includesFile", "excludesFile", "testClassesDirectory")) {
            if (property(nativePrefix + override) != null) {
                throw new IllegalArgumentException("Configure " + nativePrefix + override
                        + " in the runner POM, not as an overriding property, so ShardPilot can establish candidate coverage");
            }
        }
        if (property(mojo.runner.focusedProperty) != null || scalar("test", null) != null) {
            throw new IllegalArgumentException("Focused -D" + mojo.runner.focusedProperty
                    + " overrides native class exclusions; use an unsharded configuration without ShardPilot for focused runs");
        }
        for (String unsupported : List.of("dependenciesToScan", "suiteXmlFiles")) {
            var node = configuration.getChild(unsupported);
            if (property(unsupported) != null || property(nativePrefix + unsupported) != null
                    || node != null && (node.getChildCount() > 0 || node.getValue() != null)) {
                throw new IllegalArgumentException("ShardPilot cannot establish candidate coverage with " + unsupported);
            }
        }
        for (String flag : List.of("skip", "skipTests", "maven.test.skip")) {
            if (Boolean.parseBoolean(property(flag)) || Boolean.parseBoolean(scalar(flag, null))) {
                throw new IllegalArgumentException("ShardPilot cannot record actual selection when tests are skipped: " + flag);
            }
        }
        if (mojo.runner == TestRunner.FAILSAFE && (Boolean.parseBoolean(property("skipITs")) || Boolean.parseBoolean(scalar("skipITs", null)))) {
            throw new IllegalArgumentException("ShardPilot cannot record actual selection when integration tests are skipped");
        }
    }

    private List<String> patterns(String name) {
        var node = configuration.getChild(name);
        var values = new ArrayList<String>();
        if (node != null) for (var child : node.getChildren()) values.add(evaluate(child.getValue()));
        return values;
    }

    private String scalar(String name, String fallback) {
        var node = configuration.getChild(name);
        return node == null || node.getValue() == null ? fallback : evaluate(node.getValue());
    }

    private String evaluate(String expression) {
        try {
            Object value = expressions.evaluate(expression);
            if (value == null || value.toString().contains("${")) throw new IllegalArgumentException("Unresolved runner expression: " + expression);
            return value.toString();
        } catch (ExpressionEvaluationException exception) {
            throw new IllegalArgumentException("Cannot resolve runner configuration", exception);
        }
    }

    /** Consults all property origins: rejected overrides are unsafe regardless of where they were set. */
    private String property(String name) {
        return mojo.session.getUserProperties().getProperty(name,
                mojo.project.getProperties().getProperty(name, mojo.session.getSystemProperties().getProperty(name)));
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : mojo.project.getBasedir().toPath().resolve(path);
    }

    /** Native pattern files may contain blank lines and comments; missing configured files fail closed. */
    private static List<String> readPatterns(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    static String classFile(String name) { return name.replace('.', '/') + ".class"; }

    /** All scanned classes are needed to deny non-selected helpers as well as other shards' tests. */
    record Selection(Set<String> all, Set<String> eligible) { }
}
