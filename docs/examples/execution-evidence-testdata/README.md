# Synthetic two-node C2 example

This authored synthetic cohort is now checked against the implemented development C2 validator.
`future-c2` distinguishes its illustrative producer from the released 0.5.0 adapter.
All revision/run/job identities are illustrative. No application test payloads are used.

- `expected.json` is supplied independently by the aggregating CI job.
- `baseline.yaml` and `selection.json` are trusted cohort inputs.
- Each `node-N` contains a plan, one native XML and producer `evidence.json`.
- `report.json` matches the validator's successful aggregate report (JSON key order is immaterial).

Hashes bind exact file bytes. Keep fixtures LF-only when editing or recompute their hashes.
The contract is [described here](../../execution-evidence-contract.md). Schema-valid
documents are not necessarily semantically valid or evidence of successful execution.
