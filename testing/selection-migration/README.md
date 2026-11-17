# Selection migration acceptance tools

These are development-only comparison fixtures, not scripts required by plugin consumers.
The [guide](../../docs/selection-migration.md) documents frozen inputs, the trusted-code boundary,
exact difference review and the Gradle/Maven native workflow examples.

- `scripts/compare_selectors.py` captures and replays the explicitly trusted consumer daily selector.
- `SelectionComparisonProbe` evaluates the same frozen policy/eligibility/changes/history with core.
- `adapters/` and `verify_adapters.py` require byte-identical selection v2 from both real adapters.
- `scripts/run_selection_workflow.py` observes original native outcomes and retains C2 evidence;
  selection and partitioning remain entirely in the plugins.

Keep application-specific inputs and copied consumer code in fresh private output. The portable
regression fixture uses generic `example.*` classes; do not vendor service implementation here.
The [dated report](../../docs/reports/selection-migration-2026-11-15.md) records actual service hashes,
limitations and explained differences. No benchmark or automatic service migration is performed.
