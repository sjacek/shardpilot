package io.github.sjacek.shardpilot;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;

import java.io.Serial;
import java.io.Serializable;

/** Excludes non-owned bytecode without widening the task's existing includes or excluding directories. */
final class ShardClassFilter implements Spec<FileTreeElement>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Provider<Boolean> enabled;
    private final Provider<Integer> shardCount;
    private final Provider<Integer> shardIndex;
    private final Provider<String> history;
    private transient ShardPlan plan;

    ShardClassFilter(Provider<Boolean> enabled, Provider<Integer> shardCount,
                     Provider<Integer> shardIndex, Provider<String> history) {
        this.enabled = enabled;
        this.shardCount = shardCount;
        this.shardIndex = shardIndex;
        this.history = history;
    }

    boolean isDisabled() {
        return !enabled.get();
    }

    int count() {
        return shardCount.get();
    }

    int index() {
        return shardIndex.get();
    }

    synchronized ShardPlan plan() {
        if (plan == null) {
            try {
                ShardPlan.validateShard(count(), index());
                plan = ShardPlan.create(DurationHistory.parse(history.get()), count());
            } catch (IllegalArgumentException failure) {
                throw new GradleException(failure.getMessage(), failure);
            }
        }
        return plan;
    }

    @Override
    public boolean isSatisfiedBy(FileTreeElement element) {
        if (isDisabled()) return false;
        ShardPlan current = plan();
        if (count() == 1 || element.isDirectory()) return false;
        String path = element.getRelativePath().getPathString().replace('\\', '/');
        if (!path.endsWith(".class")) return false;
        String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        return current.shardFor(className) != index();
    }
}
