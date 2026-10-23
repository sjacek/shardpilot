package io.github.sjacek.shardpilot;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Standalone, deterministic presentation of the exact evidence used by the coverage gate.
 * No scripts, external resources or CI credentials are needed to open the downloaded report.
 */
public final class ShardCoverageHtml {
    private ShardCoverageHtml() { }

    /**
     * Presents one evaluation without reading artifacts again.
     * @param result shared validation snapshot
     * @return complete UTF-8-compatible HTML document with embedded styles
     */
    public static String render(ShardCoverage.Evaluation result) {
        boolean passed = result.failure() == null;
        var html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>ShardPilot class coverage</title>
                <style>
                :root{color-scheme:light;font-family:system-ui,sans-serif;color:#172033;background:#f3f6fb}
                body{max-width:1200px;margin:0 auto;padding:24px}h1{margin-bottom:8px}h2{margin-top:32px}
                a{color:#174ea6}p{line-height:1.6}.panel{background:white;border:1px solid #cbd5e1;border-radius:8px;padding:16px}
                .passed{border-left:6px solid #15803d}.failed{border-left:6px solid #b91c1c}
                .scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;background:white;font-size:14px}
                caption{text-align:left;padding:12px 0;color:#475569}th,td{text-align:left;padding:10px;border:1px solid #cbd5e1;vertical-align:top}
                th{background:#e8eef7}code{font-family:ui-monospace,monospace;overflow-wrap:anywhere}
                .error{color:#991b1b;white-space:pre-wrap;overflow-wrap:anywhere}.muted{color:#475569}
                tbody tr:nth-child(even){background:#f8fafc}nav{margin:16px 0}nav a{margin-right:18px}
                @media print{body{background:white;padding:0;max-width:none}.scroll{overflow:visible}}
                </style>
                </head>
                <body>
                <h1>ShardPilot class coverage</h1>
                <p class="muted">Planned class assignments compared with downloaded shard selections.</p>
                """);
        html.append("<section class=\"panel ").append(passed ? "passed" : "failed")
                .append("\" aria-label=\"Verification result\"><strong>Verification: ")
                .append(passed ? "PASSED" : "FAILED").append("</strong>");
        if (!passed) html.append("<p class=\"error\">").append(escape(result.failure().getMessage())).append("</p>");
        html.append("<p>Baseline: ").append(size(result.baseline())).append(" classes. Shards: ")
                .append(result.planned().size()).append(". Actual comparison: ")
                .append(result.verifyActual() ? "enabled" : "disabled (plan only)").append(".</p></section>\n")
                .append("<p>This report verifies class selection. It does not prove test execution or success; require every test job to pass.</p>\n")
                .append("<nav aria-label=\"Report sections\"><a href=\"#shards\">Shard summary</a><a href=\"#classes\">Class assignments</a></nav>\n");
        diagnostics(html, "baseline.yaml", result.baseline());
        html.append("<h2 id=\"shards\">Shard summary</h2><div class=\"scroll\"><table>\n")
                .append("<caption>Missing or invalid evidence is unavailable, rather than an empty selection.</caption>\n")
                .append("<thead><tr><th scope=\"col\">Shard</th><th scope=\"col\">Planned classes</th><th scope=\"col\">Actual classes</th><th scope=\"col\">Comparison</th></tr></thead><tbody>\n");
        for (int index : new TreeSet<>(result.planned().keySet())) {
            var planned = result.planned().get(index);
            var actual = result.actual().get(index);
            html.append("<tr><th scope=\"row\">").append(index).append("</th><td>").append(size(planned))
                    .append("</td><td>").append(result.verifyActual() ? size(actual) : "Not checked")
                    .append("</td><td>").append(comparison(planned, actual, result.verifyActual())).append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n");
        for (int index : new TreeSet<>(result.planned().keySet())) {
            diagnostics(html, "planned-" + index + ".yaml", result.planned().get(index));
            if (result.verifyActual()) diagnostics(html, "actual-" + index + ".yaml", result.actual().get(index));
        }
        html.append("<h2 id=\"classes\">Class assignments</h2><div class=\"scroll\"><table>\n")
                .append("<caption>Sorted binary class names. Use your browser's Find command to locate a class. Shard numbers are one-based.</caption>\n")
                .append("<thead><tr><th scope=\"col\">Class</th><th scope=\"col\">In baseline</th><th scope=\"col\">Planned shards</th><th scope=\"col\">Actual shards</th><th scope=\"col\">Comparison</th></tr></thead><tbody>\n");
        var classes = new TreeSet<>(result.baseline().classes());
        result.planned().values().forEach(manifest -> classes.addAll(manifest.classes()));
        result.actual().values().forEach(manifest -> classes.addAll(manifest.classes()));
        boolean incompletePlan = result.planned().values().stream().anyMatch(manifest -> manifest.failure() != null);
        boolean incompleteActual = result.actual().values().stream().anyMatch(manifest -> manifest.failure() != null);
        for (String name : classes) {
            List<Integer> plans = assignments(name, result.planned());
            List<Integer> actuals = assignments(name, result.actual());
            String baseline = result.baseline().failure() != null ? "Unavailable"
                    : result.baseline().classes().contains(name) ? "Yes" : "No";
            String status;
            if (!baseline.equals("Yes")) status = baseline.equals("No") ? "Outside baseline" : "Baseline unavailable";
            else if (plans.size() > 1) status = "Multiple planned shards";
            else if (incompletePlan) status = "Evidence unavailable";
            else if (plans.isEmpty()) status = "Missing from plan";
            else if (!result.verifyActual()) status = "Not checked";
            else if (incompleteActual) status = "Evidence unavailable";
            else status = plans.equals(actuals) ? "Matches" : "Differs";
            html.append("<tr><th scope=\"row\"><code>").append(escape(name)).append("</code></th><td>")
                    .append(baseline).append("</td><td>").append(indices(plans)).append("</td><td>")
                    .append(result.verifyActual() ? indices(actuals) : "Not checked")
                    .append("</td><td>").append(status).append("</td></tr>\n");
        }
        if (classes.isEmpty()) html.append("<tr><td colspan=\"5\">No class names available from valid manifests.</td></tr>\n");
        return html.append("</tbody></table></div>\n</body>\n</html>\n").toString();
    }

    private static String size(ShardCoverage.Manifest manifest) {
        return manifest == null || manifest.failure() != null ? "Unavailable" : Integer.toString(manifest.classes().size());
    }

    private static String comparison(ShardCoverage.Manifest planned, ShardCoverage.Manifest actual, boolean verifyActual) {
        if (planned.failure() != null) return "Evidence unavailable";
        if (!verifyActual) return "Not checked";
        if (actual == null || actual.failure() != null) return "Evidence unavailable";
        var missing = new TreeSet<>(planned.classes());
        missing.removeAll(actual.classes());
        var unexpected = new TreeSet<>(actual.classes());
        unexpected.removeAll(planned.classes());
        return missing.isEmpty() && unexpected.isEmpty() ? "Matches"
                : "Differs: " + missing.size() + " missing, " + unexpected.size() + " unexpected";
    }

    private static List<Integer> assignments(String name, Map<Integer, ShardCoverage.Manifest> manifests) {
        return manifests.entrySet().stream().filter(entry -> entry.getValue().classes().contains(name))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static String indices(List<Integer> indices) {
        return indices.isEmpty() ? "None" : indices.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static void diagnostics(StringBuilder html, String filename, ShardCoverage.Manifest manifest) {
        if (manifest.failure() != null) html.append("<p class=\"error\"><strong>").append(filename)
                .append(":</strong> ").append(escape(manifest.failure().getMessage())).append("</p>\n");
    }

    /** All artifact-derived text is escaped, including parser errors containing invalid YAML values. */
    private static String escape(String value) {
        if (value == null) return "Unknown error";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
