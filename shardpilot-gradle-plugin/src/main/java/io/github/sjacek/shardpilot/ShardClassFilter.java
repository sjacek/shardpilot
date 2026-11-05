package io.github.sjacek.shardpilot;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * Composes subset and ownership exclusions with native discovery. Selection is applied even at
 * count one; only partitioning becomes trivial there. Cached snapshots are transient so restoring
 * the configuration cache rebuilds them from the current managed inputs, not a previous job's plan.
 */
final class ShardClassFilter implements Spec<FileTreeElement>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Provider<Boolean> enabled;
    private final Provider<Integer> shardCount;
    private final Provider<Integer> shardIndex;
    private final Provider<String> history;
    private final Provider<String> selection;
    private transient ShardPlan plan;
    private transient Set<String> selected;

    ShardClassFilter(Provider<Boolean> enabled, Provider<Integer> shardCount,
                     Provider<Integer> shardIndex, Provider<String> history, Provider<String> selection) {
        this.enabled = enabled;
        this.shardCount = shardCount;
        this.shardIndex = shardIndex;
        this.history = history;
        this.selection = selection;
    }

    boolean isDisabled() {
        if (!enabled.get() && hasSelection()) throw new GradleException("Explicit selection requires shardPilot.enabled=true");
        return !enabled.get();
    }

    boolean hasSelection() { return !selection.get().isEmpty(); }

    private synchronized Set<String> selected() {
        if (selected == null) selected = ClassManifest.parse(selection.get(), false);
        return selected;
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
                plan = ShardPlan.create(hasSelection() ? TestSelection.history(history.get(), selected())
                        : DurationHistory.parse(history.get()), count());
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
        if (element.isDirectory()) return false;
        String path = element.getRelativePath().getPathString().replace('\\', '/');
        if (!path.endsWith(".class")) return false;
        String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        return hasSelection() && !selected().contains(className) || current.shardFor(className) != index();
    }
}
