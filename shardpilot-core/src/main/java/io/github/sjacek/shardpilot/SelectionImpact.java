package io.github.sjacek.shardpilot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;

import static io.github.sjacek.shardpilot.SelectionPolicy.*;
import static io.github.sjacek.shardpilot.SelectionDecision.Reason;

/** Declarative version-2 impact rules. No build model, source parser or application callback is executed. */
@NullMarked
public final class SelectionImpact {
    private SelectionImpact() { }

    /** Structured contracts use JSON-compatible values with lossless decimal numbers. */
    public enum Format {
        /** Strict JSON, including duplicate-key rejection. */ JSON,
        /** JSON-compatible YAML, without aliases, custom tags or complex mapping keys. */ YAML
    }

    /** Metadata-only failure classification; input values and parser errors must never escape. */
    public enum Failure {
        /** Complete semantic comparison. */ NONE,
        /** Input could not be parsed as the configured format. */ INVALID_DOCUMENT,
        /** Input/output or comparison exceeded a documented limit. */ RESOURCE_LIMIT,
        /** Git object cannot be read or is not a regular blob. */ READ_FAILED,
        /** Rules disagree about the format of one changed file. */ FORMAT_CONFLICT
    }

    /**
     * A source root maps a repository path to its binary outer family by conventional package layout.
     * @param path literal repository-relative source directory
     * @param packagePrefix optional package before the relative source path
     * @param extensions filename extensions without a dot
     */
    public record SourceRoot(String path, String packagePrefix, Set<String> extensions) {
        /** Validates literal roots; unsupported layouts require explicit path/module rules. */
        public SourceRoot {
            SelectionGlob.path(path, false);
            if (path.contains("*") || path.contains("?") || !packagePrefix.isEmpty()
                    && DurationHistory.invalidClassName(packagePrefix + ".ImpactProbe")) throw invalid();
            extensions = sorted(extensions);
            if (extensions.isEmpty() || extensions.size() > 16 || extensions.stream().anyMatch(value -> !value.matches("[A-Za-z0-9]{1,16}"))) throw invalid();
        }
    }

    /**
     * Configured dependency direction is consumer -> provider. Provider changes protect reverse dependents.
     * @param id stable module ID, not a Gradle/Maven implementation name
     * @param changes repository-relative path globs
     * @param dependsOn provider module IDs
     * @param tests protected targets; empty supports a library represented only by its consumers
     */
    public record Module(String id, Set<String> changes, Set<String> dependsOn, Targets tests) {
        /** Checks local shape; the complete graph is checked by Configuration. */
        public Module {
            identifier(id); changes = sorted(changes); dependsOn = sorted(dependsOn);
            if (changes.isEmpty() || changes.size() > 256 || dependsOn.size() > 256) throw invalid();
            changes.forEach(pattern -> SelectionGlob.path(pattern, true)); dependsOn.forEach(SelectionPolicy::identifier);
        }
    }

    /**
     * All matching pointer rules contribute. IGNORE never vetoes another rule's protected targets.
     * @param id rule ID
     * @param files repository-relative contract globs
     * @param format explicit JSON/YAML format
     * @param pointers RFC 6901 subtree roots; the empty pointer watches the whole document
     * @param action require/optional/full/ignore
     * @param tests targets, empty only for full/ignore
     * @param priority optional ordering
     */
    public record ContractRule(String id, Set<String> files, Format format, Set<String> pointers,
                               Action action, Targets tests, int priority) {
        /** Validates targets and pointers without reading any document. */
        public ContractRule {
            identifier(id); files = sorted(files); pointers = sorted(pointers);
            if (files.isEmpty() || files.size() > 256 || pointers.isEmpty() || pointers.size() > 256) throw invalid();
            files.forEach(pattern -> SelectionGlob.path(pattern, true)); pointers.forEach(StructuredContracts::pointer);
            // Reuse the existing action contract so optional/required semantics cannot drift.
            validateAction(action, tests, priority);
        }
    }

    /**
     * One immutable impact configuration; optional in v2 and absent from v1.
     * @param changedTests configured source roots for direct changed-test protection
     * @param modules acyclic explicit dependency graph
     * @param contracts structured change rules
     */
    public record Configuration(List<SourceRoot> changedTests, List<Module> modules, List<ContractRule> contracts) {
        /** Snapshots ordering, rejects duplicate IDs/roots and checks the graph before any Git I/O. */
        public Configuration {
            changedTests = List.copyOf(changedTests).stream().sorted(Comparator.comparing(SourceRoot::path)).toList();
            modules = List.copyOf(modules).stream().sorted(Comparator.comparing(Module::id)).toList();
            contracts = List.copyOf(contracts).stream().sorted(Comparator.comparing(ContractRule::id)).toList();
            if (changedTests.size() > 64 || modules.size() > 256 || contracts.size() > 256
                    || changedTests.stream().map(SourceRoot::path).distinct().count() != changedTests.size()
                    || modules.stream().map(Module::id).distinct().count() != modules.size()
                    || contracts.stream().map(ContractRule::id).distinct().count() != contracts.size()) throw invalid();
            validateGraph(modules);
        }

        /** Disabled impact extensions.
         * @return empty configuration
         */
        public static Configuration none() { return new Configuration(List.of(), List.of(), List.of()); }

        /** Whether all extensions are disabled.
         * @return no changed-test, module or contract rules
         */
        public boolean isEmpty() { return changedTests.isEmpty() && modules.isEmpty() && contracts.isEmpty(); }
    }

    /**
     * Frozen semantic change metadata; never stores document values.
     * @param format declared contract format
     * @param failure NONE means pointers are complete; otherwise impact is unknown
     * @param pointers complete changed subtree roots, empty for a semantic no-op or unknown result
     * @param beforeSha256 raw blob hash, empty for a missing side/unavailable read
     * @param afterSha256 raw blob hash, empty for a missing side/unavailable read
     */
    public record Change(Format format, Failure failure, Set<String> pointers, String beforeSha256, String afterSha256) {
        /** Bounds retained metadata and rejects unknown results containing provisional pointer selections. */
        public Change {
            pointers = sorted(pointers);
            if (pointers.size() > 4096 || failure != Failure.NONE && !pointers.isEmpty()
                    || invalidHash(beforeSha256) || invalidHash(afterSha256)
                    || failure == Failure.NONE && beforeSha256.isEmpty() && afterSha256.isEmpty()) throw invalid();
            pointers.forEach(StructuredContracts::pointer);
        }
    }

    /** A proposal, not an executable subset: the engine still validates E and packs protected/optional families. */
    record Effect(Targets tests, Action action, int priority, Reason reason, String source) { }

    /** Unknown structure is independent of path classification, so a path IGNORE cannot hide incomplete analysis. */
    record Analysis(List<Effect> effects, Set<String> classified, Set<String> sources, boolean unknown) { }

    /**
     * Accumulates all three impact sources against frozen inputs. It never discovers new classes,
     * reads documents or removes another source's proposal; native eligibility remains authoritative.
     * Source IDs describe configuration, while family expansion and cost deduplication belong to the engine.
     */
    static Analysis analyze(Configuration config, SelectionContext context, SelectionGlob.Work work) {
        var effects = new ArrayList<Effect>(); var classified = new TreeSet<String>(); var sources = new TreeSet<String>();
        var families = new TreeMap<String, Set<String>>();
        context.eligible().forEach(name -> families.computeIfAbsent(DurationHistory.family(name), ignored -> new TreeSet<>()).add(name));
        var changedModules = new TreeSet<String>(); var activatedContracts = new TreeSet<String>(); boolean unknown = false;
        for (String path : context.changes().paths()) {
            for (var root : config.changedTests()) {
                work.step();
                if (!path.startsWith(root.path() + "/")) continue;
                String relative = path.substring(root.path().length() + 1); int dot = relative.lastIndexOf('.');
                if (dot <= 0 || !root.extensions().contains(relative.substring(dot + 1))) continue;
                String binary = (root.packagePrefix().isEmpty() ? "" : root.packagePrefix() + ".") + relative.substring(0, dot).replace('/', '.');
                Set<String> members = families.get(binary);
                if (members == null) continue; // Helpers/deletions stay subject to other rules and safe unmatched fallback.
                String source = "changed-tests:" + root.path();
                effects.add(new Effect(new Targets(members, Set.of()), Action.REQUIRE, 0, Reason.CHANGED_TEST, source));
                classified.add(path); sources.add(source);
            }
            for (var module : config.modules()) {
                if (matches(module.changes(), path, work)) { changedModules.add(module.id()); classified.add(path); }
            }
            var matching = config.contracts().stream().filter(rule -> matches(rule.files(), path, work)).toList();
            if (matching.isEmpty()) continue;
            var change = context.changes().contracts().get(path);
            if (change == null || change.failure() != Failure.NONE || matching.stream().anyMatch(rule -> rule.format() != change.format())) { unknown = true; continue; }
            // A complete semantic no-op classifies the file without activating a target. The engine
            // still applies mandatory/empty-selection policy; no-op does not imply successful execution.
            classified.add(path);
            for (String changed : change.pointers()) {
                boolean covered = false;
                for (var rule : matching) {
                    boolean intersects = false;
                    for (String watched : rule.pointers()) {
                        work.step(); intersects |= StructuredContracts.intersects(watched, changed);
                        // A watched descendant reacts to removal/replacement of its parent, but does
                        // not establish coverage of every unknown sibling in that changed subtree.
                        covered |= watched.isEmpty() || watched.equals(changed) || changed.startsWith(watched + "/");
                    }
                    if (intersects) activatedContracts.add(rule.id());
                }
                if (!covered) unknown = true; // No silent discard of an unknown contract area, even with a broad path IGNORE.
            }
        }
        // Traverse provider -> consumers, including empty intermediary modules. A visited set both
        // deduplicates diamonds and prevents the number of dependency paths from multiplying work.
        var impacted = new TreeSet<>(changedModules);
        var pending = new ArrayDeque<>(changedModules);
        var dependents = dependents(config.modules());
        while (!pending.isEmpty()) for (String dependent : dependents.getOrDefault(pending.removeFirst(), Set.of())) {
            work.step(); if (impacted.add(dependent)) pending.addLast(dependent);
        }
        for (var module : config.modules()) if (impacted.contains(module.id())) {
            String source = "module:" + module.id(); sources.add(source);
            effects.add(new Effect(module.tests(), Action.REQUIRE, 0,
                    changedModules.contains(module.id()) ? Reason.CHANGED_MODULE : Reason.DEPENDENT_MODULE, source));
        }
        for (var rule : config.contracts()) if (activatedContracts.contains(rule.id())) {
            String source = "contract:" + rule.id(); sources.add(source);
            effects.add(new Effect(rule.tests(), rule.action(), rule.priority(),
                    rule.action() == Action.OPTIONAL ? Reason.OPTIONAL_CONTRACT : Reason.REQUIRED_CONTRACT, source));
        }
        return new Analysis(effects, classified, sources, unknown);
    }

    private static boolean matches(Set<String> patterns, String path, SelectionGlob.Work work) {
        return patterns.stream().anyMatch(pattern -> SelectionGlob.matches(pattern, path, "/", work));
    }

    /** Reverse adjacency is explicit; no Gradle dependency or Maven reactor lookup occurs here. */
    private static Map<String, Set<String>> dependents(List<Module> modules) {
        var result = new TreeMap<String, Set<String>>(); modules.forEach(module -> result.put(module.id(), new TreeSet<>()));
        modules.forEach(module -> module.dependsOn().forEach(provider -> {
            if (!result.containsKey(provider)) throw invalid(); result.get(provider).add(module.id());
        }));
        return result;
    }

    /** Kahn's algorithm rejects cycles without recursive stack growth on a long dependency chain. */
    private static void validateGraph(List<Module> modules) {
        var reverse = dependents(modules); var degrees = new TreeMap<String, Integer>(); var ready = new ArrayDeque<String>();
        modules.forEach(module -> { degrees.put(module.id(), module.dependsOn().size()); if (module.dependsOn().isEmpty()) ready.add(module.id()); });
        int visited = 0;
        while (!ready.isEmpty()) {
            visited++;
            for (String dependent : reverse.getOrDefault(ready.removeFirst(), Set.of())) {
                int degree = degrees.getOrDefault(dependent, 0) - 1; degrees.put(dependent, degree);
                if (degree == 0) ready.add(dependent);
            }
        }
        if (visited != modules.size()) throw invalid();
    }

    private static boolean invalidHash(String hash) { return !hash.isEmpty() && !hash.matches("[a-f0-9]{64}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid selection impact configuration or metadata"); }
}
