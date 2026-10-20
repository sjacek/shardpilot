package io.github.sjacek.shardpilot;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

/** Adds opt-in sharding to existing and lazily registered Test tasks without applying JVM plugins. */
@SuppressWarnings("unused") // Gradle instantiates this entry point through the generated plugin descriptor.
public final class ShardPilotPlugin implements Plugin<Project> {
    /** Creates the entry point instantiated by Gradle's plugin manager. */
    public ShardPilotPlugin() { }

    @Override
    public void apply(Project project) {
        var providers = project.getProviders();
        project.getTasks().withType(Test.class).configureEach(test -> {
            var settings = test.getExtensions().create("shardPilot", ShardPilotExtension.class);
            settings.getEnabled().convention(false);
            settings.getShardCount().convention(providers.gradleProperty("shardpilot.count")
                    .map(Integer::parseInt).orElse(1));
            settings.getShardIndex().convention(providers.gradleProperty("shardpilot.index")
                    .map(Integer::parseInt).orElse(1));
            var history = providers.fileContents(settings.getDurationsFile()).getAsText().orElse("");
            var filter = new ShardClassFilter(settings.getEnabled(), settings.getShardCount(),
                    settings.getShardIndex(), history);

            // Include the plan inputs explicitly: a cached shard must never stand in for another shard.
            test.getInputs().property("shardpilot.enabled", settings.getEnabled());
            test.getInputs().property("shardpilot.count", settings.getShardCount());
            test.getInputs().property("shardpilot.index", settings.getShardIndex());
            test.getInputs().property("shardpilot.history", history);
            // Exclusions compose with existing includes using AND, never widening the original suite.
            test.exclude(filter);
            test.doFirst(new PrepareShardAction(filter));
        });
    }
}
