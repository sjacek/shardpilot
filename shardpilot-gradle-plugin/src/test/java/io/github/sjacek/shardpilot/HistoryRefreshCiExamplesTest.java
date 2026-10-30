package io.github.sjacek.shardpilot;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards consumer CI examples against dropping native gates, rerunning producers in aggregation
 * or flattening parallel XML downloads. These structural checks do not claim live GitLab execution;
 * native TestKit/Invoker builds independently verify the adapter behavior behind each command.
 */
class HistoryRefreshCiExamplesTest {
    @ParameterizedTest
    @ValueSource(strings = {"gradle", "maven"})
    void everyNodeAndBothDiagnosticGatesRemainRequired(String adapter) throws IOException {
        Map<?, ?> ci = example(adapter);
        assertEquals(List.of("prepare", "test", "aggregate"), ci.get("stages"));
        assertEquals(3, job(ci, "test").get("parallel"));
        assertNeeds(ci, job(ci, "test"), Set.of("plan"));
        for (String name : List.of("plan", "test", "coverage", "durations")) {
            assertFalse(job(ci, name).containsKey("allow_failure"), "Examples must retain native and artifact failures");
            for (String command : strings(job(ci, name).get("script"))) {
                assertFalse(command.contains("|| true"), "Do not turn a failed producer or gate into success");
            }
        }
        for (String name : List.of("coverage", "durations")) {
            Map<?, ?> gate = job(ci, name);
            assertNeeds(ci, gate, Set.of("plan", "test"));
            assertEquals("aggregate", gate.get("stage"));
            assertEquals("always", gate.get("when"), "Diagnostics must be scheduled after failed native nodes");
            assertEquals("always", mapping(gate.get("artifacts")).get("when"), "Failed scripts must retain diagnostics");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle", "maven"})
    void parallelXmlAndSelectionDownloadsKeepTheirOwnNamespaces(String adapter) throws IOException {
        Map<?, ?> ci = example(adapter);
        Map<?, ?> node = job(ci, "test");
        String suite = adapter.equals("gradle") ? "test" : "unit";
        String copiedTree = "collected/" + suite + "/";
        String destination = copiedTree + "shard-$CI_NODE_INDEX";
        List<String> commands = strings(node.get("after_script"));
        assertTrue(commands.stream().anyMatch(command -> command.contains("mkdir -p \"" + destination + "\"")));
        assertTrue(commands.stream().anyMatch(command -> command.contains("cp -R ") && command.contains(destination + "/")));
        String nativeInvocation = String.join(" ", strings(node.get("script")));
        assertTrue(nativeInvocation.contains("shardpilot.index=\"$CI_NODE_INDEX\""));
        assertTrue(nativeInvocation.contains("shardpilot.count=3"));
        Map<?, ?> artifacts = mapping(node.get("artifacts"));
        assertEquals("always", artifacts.get("when"));
        assertTrue(artifacts.get("name").toString().contains("$CI_COMMIT_SHA-$CI_NODE_INDEX"));
        List<String> paths = strings(artifacts.get("paths"));
        assertTrue(paths.contains(copiedTree));
        assertTrue(paths.stream().anyMatch(path -> path.endsWith("/actual/")));
        assertFalse(paths.stream().anyMatch(path -> path.endsWith("/plan/")), "Only preparation publishes the common plan");
        assertFalse(paths.stream().anyMatch(path -> path.contains("test-results") || path.contains("surefire-reports")),
                "Native paths can collide when identical XML filenames are downloaded from all nodes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle", "maven"})
    void artifactOnlyCommandsKeepCoverageAndCandidateReportsSeparate(String adapter) throws IOException {
        Map<?, ?> ci = example(adapter);
        String coverageGoal = adapter.equals("gradle") ? "verifyTestShards" : "shardpilot:verify";
        String refreshGoal = adapter.equals("gradle") ? "refreshTestShardDurations" : "shardpilot:refresh-durations";
        for (String name : List.of("coverage", "durations")) {
            List<String> scripts = strings(job(ci, name).get("script"));
            assertEquals(1, scripts.size());
            List<String> tokens = List.of(scripts.get(0).split("\\s+"));
            assertTrue(tokens.contains(name.equals("coverage") ? coverageGoal : refreshGoal));
            assertTrue(tokens.stream().noneMatch(Set.of("clean", "test", "verify", "test-compile", "prepareTestShardManifests", "shardpilot:prepare")::contains),
                    "Aggregation reads existing evidence rather than scheduling or deleting producers");
            assertTrue(tokens.stream().anyMatch(token -> token.endsWith("shardpilot.count=3")));
        }
        List<String> coverage = strings(mapping(job(ci, "coverage").get("artifacts")).get("paths"));
        List<String> durations = strings(mapping(job(ci, "durations").get("artifacts")).get("paths"));
        assertTrue(coverage.stream().anyMatch(path -> path.endsWith("/coverage.json")));
        assertTrue(coverage.stream().anyMatch(path -> path.endsWith("/coverage.html")));
        assertTrue(durations.stream().anyMatch(path -> path.endsWith("/durations.tsv")));
        assertTrue(durations.stream().anyMatch(path -> path.endsWith("/durations.json")));
        assertTrue(coverage.stream().noneMatch(durations::contains), "Candidate and selection evidence must not overwrite each other");
        if (adapter.equals("maven")) {
            assertTrue(strings(job(ci, "durations").get("script")).get(0).contains("-Dshardpilot.reports=collected/unit"),
                    "The standalone goal does not infer the CI download location from native reportsDirectory");
        }
    }

    private static Map<?, ?> example(String adapter) throws IOException {
        var parser = new Load(LoadSettings.builder().setAllowDuplicateKeys(false).build());
        // Independent roots use the standard filename understood by GitLab tooling and IDE navigation.
        return mapping(parser.loadFromString(Files.readString(Path.of("examples/ci", adapter, ".gitlab-ci.yml"))));
    }

    private static Map<?, ?> job(Map<?, ?> document, String name) { return mapping(document.get(name)); }

    private static Map<?, ?> mapping(Object value) { return assertInstanceOf(Map.class, value); }

    private static List<String> strings(Object value) {
        List<?> items = assertInstanceOf(List.class, value);
        return items.stream().map(item -> assertInstanceOf(String.class, item)).toList();
    }

    private static void assertNeeds(Map<?, ?> document, Map<?, ?> job, Set<String> expected) {
        // Short local needs have artifacts=true by default; every referenced name must be a real job.
        List<String> needs = strings(job.get("needs"));
        assertEquals(expected.size(), needs.size());
        assertEquals(expected, Set.copyOf(needs));
        for (String need : needs) assertInstanceOf(Map.class, document.get(need), "Undefined job " + need);
    }
}
