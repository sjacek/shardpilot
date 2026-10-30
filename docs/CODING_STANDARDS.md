# Coding Standards

These rules supplement the architecture, verification and release requirements in
[Contributing](../CONTRIBUTING.md).

## Declaration Spacing

- `LAYOUT-01`: Separate consecutive methods and constructors with exactly one empty line. Keep each declaration's documentation comments and annotations attached to that declaration; place the separating empty line before its documentation or annotations, not between them and the declaration.
- `LAYOUT-02`: In entity classes, separate consecutive fields (attributes) with exactly one empty line. Keep field comments and annotations attached to their field. For Java persistence models, this includes `@Entity`, `@Embeddable` and `@MappedSuperclass` classes, including fully qualified annotations.

## Annotation Layout

- `LAYOUT-03`: Put each declaration annotation on its own line before a class, interface, enum, record, method, constructor or field. Start the next annotation or the annotated declaration on a new line, aligned at the declaration's indentation. Keep documentation before the annotations; nested annotations inside annotation arguments are part of that argument expression. Parameter and type-use annotations stay next to their parameter or type. Apply the same separate-line layout to TypeScript declaration decorators and Groovy declaration annotations.

## CI Examples And Validation

- `CI-01`: Keep independent CI examples self-contained and clearly identify the consumer configuration they require. Every local `needs` reference must resolve to a job defined in the same document, unless an external include is explicitly part of the example and its verification.
- `CI-02`: Parse the actual example files in structural tests, rejecting duplicate YAML keys. Verify job references, required native test gates, shard count, artifact dependencies and aggregation commands. Declare these files as build inputs so editing an example invalidates cached verification results.
- `CI-03`: Preserve native test failures and required verification gates. Diagnostic scheduling and artifact retention after failure are separate settings; neither may turn a failed test into success or make a required gate optional.
- `CI-04`: Keep each shard's extracted reports in a distinct directory. Aggregation reads the original collected evidence and must not run `clean`, rerun tests or regenerate missing plans. Class-selection manifests establish candidate ownership; normal test results and CI establish execution success.
- `CI-05`: Validate the parsed configuration independently of IDE inspection results. Structural tests do not establish successful execution on a live CI provider; report that distinction explicitly. See the [consumer CI examples](../examples/ci/README.md).

## IDE Diagnostics And Inspection Suppressions

- `IDE-01`: Investigate unresolved build-tool types by checking the imported Gradle/Maven model, selected distribution, JVM and synchronization errors. Link independent examples separately when needed. Preserve correct API usage; remove imports only when they are redundant, rather than to conceal a missing IDE model.
- `IDE-02`: Distinguish live editor diagnostics, batch/pre-commit analysis and command-line validation. Reproduce the reported analysis mode where possible. A clean editor view or an old Code Analysis snapshot does not prove that the current pre-commit inspection passes.
- `IDE-03`: Fix genuine defects before considering suppression. For a confirmed false positive, use the smallest applicable suppression with the exact inspection ID, immediately before the affected element. Explain the reason and identify the independent validation that covers the affected contract. Do not disable an inspection project-wide to silence an isolated example warning.
- `IDE-04`: In the standalone GitLab examples, `#noinspection UndefinedJob` may be used immediately before an affected `needs` key for the observed pre-commit false positive. Keep `HistoryRefreshCiExamplesTest` checking every referenced job. The comment must not conceal a missing job or replace YAML/CI validation; see the [documented workaround](../examples/ci/README.md).
- `IDE-05`: Keep local IDE paths and machine-specific settings outside version control. Record verification limits honestly when the IDE or runner is unavailable. Review a workaround after upgrading the IDE/plugin and remove it once the affected analysis mode passes without it.
