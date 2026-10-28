package io.github.sjacek.shardpilot;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.testing.Test;

/** Configuration-only wiring. Execution actions retain managed values, never Project or Test. */
final class ShardHistory {
    private ShardHistory() { }

    static void configure(Test test, ShardPilotExtension settings, ShardHistoryOptions options,
                          RegularFileProperty verificationBaseline, Property<Integer> verificationCount) {
        var project = test.getProject();
        String suffix = Character.toUpperCase(test.getName().charAt(0)) + test.getName().substring(1);
        var previous = project.getProviders().fileContents(settings.getDurationsFile()).getAsText().orElse("");
        // Dereference FileCollections through providers to remove producer task dependencies.
        // Passing source-set outputs or report producers directly would silently schedule compilation/tests.
        var reports = project.getProviders().provider(options.getReports()::getFiles);
        var explicitClasses = project.getProviders().provider(options.getClassDirectories()::getFiles);
        var defaults = project.getProviders().provider(test.getTestClassesDirs()::getFiles);
        var count = verificationCount.orElse(settings.getShardCount());
        project.getTasks().register("refresh" + suffix + "ShardDurations", RefreshShardDurations.class, task -> {
            task.setGroup("verification");
            task.setDescription("Refreshes family duration history from existing reports for " + test.getName() + ".");
            task.getReports().from(reports);
            task.getClassDirectories().from(explicitClasses);
            task.getDefaultClassDirectories().from(defaults);
            task.getBaselineFile().set(options.getBaselineFile());
            task.getVerificationBaseline().set(verificationBaseline);
            task.getPreviousHistory().set(previous);
            task.getDurationsFile().set(settings.getDurationsFile());
            task.getShardCount().set(count);
            task.getMinimumSeconds().set(options.getMinimumSeconds());
            task.getPrecision().set(options.getPrecision());
            task.getIncludeFailedSuites().set(options.getIncludeFailedSuites());
            task.getDuplicates().set(options.getDuplicates());
            task.getMinimumCoverage().set(options.getMinimumCoverage());
            task.getPruning().set(options.getPruning());
            task.getOutputFile().set(options.getOutputFile());
            task.getReportFile().set(options.getReportFile());
            task.mustRunAfter(test);
        });
    }
}
