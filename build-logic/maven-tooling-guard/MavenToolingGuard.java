package io.github.sjacek.build;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Producer-only core extension; consumers receive neither this guard nor its scope policy. */
public final class MavenToolingGuard extends AbstractMavenLifecycleParticipant {
    private static final String SITE = "org.apache.maven.plugins:maven-site-plugin";

    private final Properties policy = new Properties();

    public MavenToolingGuard() throws IOException {
        try (var source = getClass().getResourceAsStream("/maven-tooling.properties")) {
            if (source == null) {
                throw new IOException("Missing reviewed Maven tooling policy");
            }
            policy.load(source);
        }
    }

    /** Reject actual CLI goals, including MAVEN_ARGS and prefix/fully qualified Site forms. */
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        checkGoals(session.getRequest().getGoals());
    }

    private void checkGoals(List<String> goals) throws MavenExecutionException {
        for (String goal : goals) {
            if (Set.of("pre-site", "site", "post-site", "site-deploy").contains(goal)) {
                reject("Site lifecycle is forbidden: " + goal);
            }
            String[] parts = goal.split(":");
            if (parts.length > 1) {
                String coordinate = parts.length == 2 ? policy.getProperty("prefix." + parts[0]) : parts[0] + ":" + parts[1];
                if (coordinate == null || !policy.containsKey("plugin." + coordinate)) {
                    reject("Unclassified Maven plugin goal: " + goal);
                }
                if (!"blocking".equals(policy.getProperty("plugin." + coordinate))) {
                    reject("Inherited-only Maven plugin cannot execute: " + goal);
                }
            }
        }
    }

    /** Inspect effective reactor models before native lifecycle execution, including active profiles. */
    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        for (var project : session.getProjects()) {
            if (project.getDefaultGoal() != null) {
                checkGoals(List.of(project.getDefaultGoal().trim().split("\\s+")));
            }
            inspect(project.getBuildPlugins());
            if (project.getPluginManagement() != null) {
                inspect(project.getPluginManagement().getPlugins());
            }
            if (!project.getBuildExtensions().isEmpty()) {
                reject("Unclassified Maven build extension in " + project.getArtifactId());
            }
            if (project.getModel().getReporting() != null) {
                for (var report : project.getModel().getReporting().getPlugins()) {
                    check(report.getGroupId() + ":" + report.getArtifactId());
                }
            }
        }
    }

    private void inspect(List<Plugin> plugins) throws MavenExecutionException {
        for (Plugin plugin : plugins) {
            String coordinate = plugin.getGroupId() + ":" + plugin.getArtifactId();
            check(coordinate);
            if (SITE.equals(coordinate)) {
                if (plugin.isExtensions()) {
                    reject("Maven Site cannot be a build extension");
                }
                for (PluginExecution execution : plugin.getExecutions()) {
                    boolean defaultSite = "default-site".equals(execution.getId()) && "site".equals(execution.getPhase())
                            && execution.getGoals().equals(List.of("site"));
                    boolean defaultDeploy = "default-deploy".equals(execution.getId()) && "site-deploy".equals(execution.getPhase())
                            && execution.getGoals().equals(List.of("deploy"));
                    if (!defaultSite && !defaultDeploy) {
                        reject("Maven Site execution is outside inherited default bindings");
                    }
                }
            }
        }
    }

    private void check(String coordinate) throws MavenExecutionException {
        if (!policy.containsKey("plugin." + coordinate)) {
            reject("Unclassified Maven plugin: " + coordinate);
        }
    }

    private static void reject(String reason) throws MavenExecutionException {
        throw new MavenExecutionException("ShardPilot tooling guard: " + reason, (Throwable) null);
    }
}
