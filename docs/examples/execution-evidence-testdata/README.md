# Synthetic two-node C2 example

This is an authored contract example, not output of an implemented C2 validator.
`future-c2` explicitly distinguishes it from the released 0.5.0 adapter.
All revision/run/job identities are illustrative. No application test payloads are used.

- `expected.json` is supplied independently by the aggregating CI job.
- `baseline.yaml` and `selection.json` are trusted cohort inputs.
- Each `node-N` contains a plan, one native XML and producer `evidence.json`.
- `report.json` illustrates the proposed successful aggregate report.

Hashes bind exact file bytes. Keep fixtures LF-only when editing or recompute their hashes.
The contract is [described here](../../execution-evidence-contract.md). Schema-valid
documents are not necessarily semantically valid or evidence of successful execution.
