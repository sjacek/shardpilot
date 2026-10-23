package io.github.sjacek.shardpilot;

import java.util.Arrays;
import java.util.LinkedHashMap;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/** Builds schema-valid test input in supplied order, deliberately preserving duplicate names. */
final class ManifestFixtures {
    private ManifestFixtures() { }

    static String yaml(String... names) {
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", 1);
        document.put("classes", Arrays.asList(names));
        return new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build()).dumpToString(document);
    }
}
