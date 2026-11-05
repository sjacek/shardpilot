package io.github.sjacek.shardpilot;

import org.gradle.api.tasks.testing.Test;

/**
 * Registers sibling tasks during project configuration. Actions retain managed file/provider inputs,
 * not Project/Test instances, so task execution can reuse the configuration cache.
 */
final class ShardVerification {
    private ShardVerification() { }

    static void configure(Test test, ShardPilotExtension settings, ShardVerificationOptions options) {
        var project = test.getProject();
        String suffix = Character.toUpperCase(test.getName().charAt(0)) + test.getName().substring(1);
        var history = project.getProviders().fileContents(settings.getDurationsFile()).getAsText().orElse("");
        var identity = settings.selectionIdentity();
        var plannedDirectory = settings.selectedDirectory(options.getPlannedDirectory());
        var actualDirectory = settings.selectedDirectory(options.getActualDirectory());
        var reportFile = settings.selectedFile(options.getReportFile());
        var htmlReportFile = settings.selectedFile(options.getHtmlReportFile());
        var shardIndex = settings.getShardIndex();
        var prepare = project.getTasks().register("prepare" + suffix + "ShardManifests", PrepareShardManifests.class, task -> {
            task.setGroup("verification");
            task.setDescription("Records unsharded eligibility and all planned partitions for " + test.getName() + ".");
            task.getClasses().from(options.getEligibleClasses());
            task.getClassDirectories().from(options.getClassDirectories());
            task.getShardCount().set(options.getShardCount());
            task.getHistory().set(history);
            task.getSelectionManifest().set(settings.selectedManifest());
            task.getSelectionIdentity().set(identity);
            task.getSelectionShardCount().set(settings.getShardCount());
            task.getDirectory().set(plannedDirectory);
        });
        var actual = project.getTasks().register("write" + suffix + "ActualShardManifest", WriteShardManifest.class, task -> {
            task.setGroup("verification");
            task.setDescription("Records actual candidate bytecode before " + test.getName() + " executes.");
            task.getClasses().from(test.getCandidateClassFiles());
            task.getClassDirectories().from(test.getTestClassesDirs());
            task.getShardCount().set(settings.getShardCount());
            task.getShardIndex().set(settings.getShardIndex());
            task.getManifest().set(actualDirectory.flatMap(dir -> shardIndex.map(index -> dir.file("actual-" + index + ".yaml"))));
            task.getSelectionIdentity().set(identity);
            // Output location must not query a producer's contents during configuration-cache storage.
            task.getIdentityFile().set(actualDirectory.flatMap(dir -> shardIndex.map(index -> dir.file("selection-" + index + ".id"))));
        });
        // Persist selection before workers start: a failed test/OOM may prevent normal reports.
        test.dependsOn(actual);
        project.getTasks().register("verify" + suffix + "Shards", VerifyShardManifests.class, task -> {
            task.setGroup("verification");
            task.setDescription("Verifies complete, disjoint, matching downloaded shard selections for " + test.getName() + ".");
            task.getPlannedDirectory().set(plannedDirectory);
            task.getActualDirectory().set(actualDirectory);
            task.getShardCount().set(options.getShardCount());
            task.getVerifyActual().set(options.getVerifyActual());
            task.getRequireNonEmptyShards().set(options.getRequireNonEmptyShards());
            task.getRequireSelection().set(settings.selectionEnabled());
            task.getReportFile().set(reportFile);
            task.getHtmlReportFile().set(htmlReportFile);
            // Ordering permits an explicit local combined invocation but schedules no producer in CI.
            task.mustRunAfter(prepare, actual);
            // No dependsOn: aggregation must inspect downloads, never replace missing/tampered evidence.
        });
    }
}
