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
        var prepare = project.getTasks().register("prepare" + suffix + "ShardManifests", PrepareShardManifests.class, task -> {
            task.setGroup("verification");
            task.setDescription("Records unsharded eligibility and all planned partitions for " + test.getName() + ".");
            task.getClasses().from(options.getEligibleClasses());
            task.getClassDirectories().from(options.getClassDirectories());
            task.getShardCount().set(options.getShardCount());
            task.getHistory().set(history);
            task.getDirectory().set(options.getPlannedDirectory());
        });
        var actual = project.getTasks().register("write" + suffix + "ActualShardManifest", WriteShardManifest.class, task -> {
            task.setGroup("verification");
            task.setDescription("Records actual candidate bytecode before " + test.getName() + " executes.");
            task.getClasses().from(test.getCandidateClassFiles());
            task.getClassDirectories().from(test.getTestClassesDirs());
            task.getShardCount().set(settings.getShardCount());
            task.getShardIndex().set(settings.getShardIndex());
            task.getManifest().set(options.getActualDirectory().file(settings.getShardIndex().map(index -> "actual-" + index + ".yaml")));
        });
        // Persist selection before workers start: a failed test/OOM may prevent normal reports.
        test.dependsOn(actual);
        project.getTasks().register("verify" + suffix + "Shards", VerifyShardManifests.class, task -> {
            task.setGroup("verification");
            task.setDescription("Verifies complete, disjoint, matching downloaded shard selections for " + test.getName() + ".");
            task.getPlannedDirectory().set(options.getPlannedDirectory());
            task.getActualDirectory().set(options.getActualDirectory());
            task.getShardCount().set(options.getShardCount());
            task.getVerifyActual().set(options.getVerifyActual());
            task.getRequireNonEmptyShards().set(options.getRequireNonEmptyShards());
            task.getReportFile().set(options.getReportFile());
            task.getHtmlReportFile().set(options.getHtmlReportFile());
            // Ordering permits an explicit local combined invocation but schedules no producer in CI.
            task.mustRunAfter(prepare, actual);
            // No dependsOn: aggregation must inspect downloads, never replace missing/tampered evidence.
        });
    }
}
