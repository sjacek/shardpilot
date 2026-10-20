package io.github.sjacek.shardpilot;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.jspecify.annotations.NonNull;

import java.io.Serial;
import java.io.Serializable;

/** Validates before the worker starts; logs metadata only and never changes failure/retry policy. */
final class PrepareShardAction implements Action<Task>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ShardClassFilter filter;

    PrepareShardAction(ShardClassFilter filter) {
        this.filter = filter;
    }

    @Override
    public void execute(@NonNull Task task) {
        if (filter.isDisabled()) return;
        ShardPlan plan = filter.plan();
        task.getLogger().lifecycle("ShardPilot {}: shard {}/{}, {} recorded class families, {} recorded seconds.",
                task.getPath(), filter.index(), filter.count(), plan.recordedFamilies(),
                plan.recordedSeconds(filter.index()));
    }
}
