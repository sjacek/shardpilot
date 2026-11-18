# Synthetic two-node C2 example

This authored synthetic cohort is checked against the C2 validator published in 0.6.0.
Its historical `future-c2` label identifies an illustrative producer; native adapter configuration
is described in the [execution-evidence guide](../../execution-evidence-contract.md).
All revision/run/job identities are illustrative. No application test payloads are used.

- `expected.json` is supplied independently by the aggregating CI job.
- `baseline.yaml` and `selection.json` are trusted cohort inputs.
- Each `node-N` contains a plan, one native XML and producer `evidence.json`.
- `report.json` matches the validator's successful aggregate report (JSON key order is immaterial).

Hashes bind exact file bytes. Keep fixtures LF-only when editing or recompute their hashes.
The contract is [described here](../../execution-evidence-contract.md). Schema-valid
documents are not necessarily semantically valid or evidence of successful execution.
