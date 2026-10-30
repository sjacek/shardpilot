# PlantUML diagrams

[Documentation index](../README.md)

The `.puml` files are editable diagram sources. SVG files beside them are generated previews
committed for Markdown viewers that cannot render PlantUML. Change a source and regenerate its
preview together; do not edit an SVG by hand.

| Diagram | Source | Preview | Explains |
| --- | --- | --- | --- |
| Components | [components.puml](components.puml) | [components.svg](components.svg) | Gradle integration, optional test-process settings and implementation responsibilities. |
| Source modules | [modules.puml](modules.puml) | [modules.svg](modules.svg) | Shared core, sibling Gradle/Maven adapters and preserved artifact coordinates. |
| Maven execution | [maven-execution.puml](maven-execution.puml) | [maven-execution.svg](maven-execution.svg) | Test compilation, native runner selection and standalone artifact verification. |
| Release publication | [publication.puml](publication.puml) | [publication.svg](publication.svg) | Automatic Gradle tag publication and later manual Maven publication from the same tag, each with a complete release gate. |
| Assignment | [shard-assignment.puml](shard-assignment.puml) | [shard-assignment.svg](shard-assignment.svg) | Measured-family packing, hash fallback and candidate exclusion. |
| History refresh | [history-refresh.puml](history-refresh.puml) | [history-refresh.svg](history-refresh.svg) | One report cohort, shared normalization, success/failure artifacts and reviewed adoption. |
| History pruning | [history-pruning.puml](history-pruning.puml) | [history-pruning.svg](history-pruning.svg) | Explicit source precedence, adapter defaults, disabled pruning and missing-source failure. |
| History recovery | [history-recovery.puml](history-recovery.puml) | [history-recovery.svg](history-recovery.svg) | Valid class selections with missing XML, candidate cleanup, same-cohort restoration and independent native gates. |
| CI execution | [ci-execution.puml](ci-execution.puml) | [ci-execution.svg](ci-execution.svg) | Independent jobs, consistent inputs and CI-owned result collection. |
| Manifest tasks | [manifest-tasks.puml](manifest-tasks.puml) | [manifest-tasks.svg](manifest-tasks.svg) | Compilation dependencies, pre-worker evidence and aggregation ordering without reruns. |
| Manifest CI flow | [manifest-ci.puml](manifest-ci.puml) | [manifest-ci.svg](manifest-ci.svg) | Common planning artifacts, every actual node selection and required CI outcomes. |
| Manifest validation | [manifest-validation.puml](manifest-validation.puml) | [manifest-validation.svg](manifest-validation.svg) | Validation policy, plan-only mode and passed/failed JSON/HTML outcomes. |
| HTML report | [html-report.puml](html-report.puml) | [html-report.svg](html-report.svg) | One evidence snapshot, both report formats, partial diagnostics and independent CI/reviewer consumers. |
| Testcontainers lifecycle | [testcontainers-lifecycle.puml](testcontainers-lifecycle.puml) | [testcontainers-lifecycle.svg](testcontainers-lifecycle.svg) | Shared PostgreSQL/RabbitMQ fixtures within a worker, isolated test data and cleanup. |
| Spock lifecycle | [spock-testcontainers-lifecycle.puml](spock-testcontainers-lifecycle.puml) | [spock-testcontainers-lifecycle.svg](spock-testcontainers-lifecycle.svg) | `setupSpec`, shared database URLs, feature iteration ownership and worker-scoped containers. |
| TestNG lifecycle | [testng-testcontainers-lifecycle.puml](testng-testcontainers-lifecycle.puml) | [testng-testcontainers-lifecycle.svg](testng-testcontainers-lifecycle.svg) | Inherited `@BeforeClass`, class database URLs, provider-row ownership and worker-scoped containers. |

## Local rendering

Use a local Java runtime, a PlantUML JAR and Graphviz `dot` available on `PATH`. PlantUML is a
documentation tool, not a ShardPilot runtime/build dependency. No renderer JAR or machine-specific
installation path is committed to this repository. No diagram uses remote includes or a rendering
service. The initial previews were rendered with PlantUML 1.2026.8; the 0.3.0 manifest diagrams
and refreshed component/CI diagrams use PlantUML 1.2026.9. The 0.4.0 history diagrams and updated
module/Maven flow previews use the same 1.2026.9 renderer.

From the repository root, set `PLANTUML_JAR` to your local JAR path. With the current PlantUML CLI:

```shell
java -Djava.awt.headless=true -jar "$PLANTUML_JAR" --check-syntax "docs/diagrams/*.puml"
java -Djava.awt.headless=true -jar "$PLANTUML_JAR" --check-before-run --format svg --disable-metadata "docs/diagrams/*.puml"
java scripts/NormalizeSvg.java docs/diagrams/*.svg
```

PowerShell, using the equivalent environment variable:

```powershell
java '-Djava.awt.headless=true' -jar $env:PLANTUML_JAR --check-syntax 'docs/diagrams/*.puml'
java '-Djava.awt.headless=true' -jar $env:PLANTUML_JAR --check-before-run --format svg --disable-metadata 'docs/diagrams/*.puml'
$previewPaths = (Get-ChildItem docs/diagrams -Filter *.svg -File).FullName
java scripts/NormalizeSvg.java @previewPaths
```

These commands generate and normalize SVGs next to the sources. Check each process exit code and
visually review each affected diagram for clipping, overlaps, readable labels and correct relationships. For a PNG
review, choose an absolute output directory under ignored `build/`; PlantUML resolves a relative
output directory against the input file's directory, not the shell working directory.

If your installed renderer uses older option names, consult its `--help` output and the official
[command-line reference](https://plantuml.com/command-line) and [SVG guide](https://plantuml.com/svg).
Keep the source and preview compatible with a pinned renderer version when reproducible diffs matter.

## SVG compatibility

PlantUML 1.2026.8 emits SVG 1.1 previews with `lengthAdjust` on a group, interactive `data-*`
attributes and an unused `xlink` declaration. Strict SVG 1.1 IDE validation reports these attributes
even though browsers display the diagrams. `lengthAdjust` belongs on text elements, not groups.

The repository's [normalizer](../../scripts/NormalizeSvg.java) runs on a JDK without external
dependencies. It moves group text adjustment to the text elements, retaining explicit overrides;
removes unqualified interactive metadata from these static previews; and removes the root `xlink`
declaration only when it is unused. Geometry, labels, IDs and actual links are preserved. XML DTDs
and external entities are rejected. This is a generation step for our previews, not a PlantUML patch
or a blanket suppression of SVG inspections.

Run normalization after every SVG render. `./gradlew :shardpilot-gradle-plugin:test --tests NormalizeSvgTest` verifies the
normalizer and the committed previews; it is also part of `check`. The utility is compiled for tests
only and is not included in the plugin's runtime, sources or Javadoc artifacts.

## Maintenance checks

- Update diagrams when changing component responsibilities, assignment rules or CI boundaries.
- Keep algorithm details consistent with `ShardPlan` and exclusion behavior with `ShardClassFilter`.
- Show Gradle/framework execution and CI result collection as external responsibilities, not plugin services.
- Keep examples generic; never copy private service topology, credentials or application data.
- Validate Markdown links and commit both the `.puml` source and refreshed `.svg` preview.
