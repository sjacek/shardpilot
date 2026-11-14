"""Check authored contract examples and their byte bindings, not a general C2 implementation.

Full Draft 2020-12 schema validation is a separate documented check. These stdlib checks
keep examples reproducible in CI without adding a schema library to either distribution.
"""
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = ROOT / 'docs/examples/execution-evidence-testdata'
SCHEMAS = ROOT / 'docs/schemas'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ExecutionEvidenceContractExamplesTest(unittest.TestCase):
    def test_every_schema_reference_resolves_offline(self):
        documents = {path.name: read(path) for path in SCHEMAS.glob('*-v1.schema.json')}
        self.assertEqual(3, len(documents))

        def visit(value, current):
            if isinstance(value, dict):
                if '$ref' in value:
                    filename, _, pointer = value['$ref'].partition('#')
                    target = documents[filename] if filename else current
                    for part in pointer.split('/')[1:]:
                        target = target[part.replace('~1', '/').replace('~0', '~')]
                for child in value.values():
                    visit(child, current)
            elif isinstance(value, list):
                for child in value:
                    visit(child, current)

        for document in documents.values():
            self.assertEqual('https://json-schema.org/draft/2020-12/schema', document['$schema'])
            self.assertFalse(document['additionalProperties'])
            visit(document, document)

    def test_selection_is_canonical_and_bound_to_expected_cohort(self):
        expected = read(EXAMPLE / 'expected.json')
        selection = EXAMPLE / 'selection.json'
        self.assertEqual(json.dumps({'schemaVersion': 1, 'mode': 'full'}, sort_keys=True,
                                    separators=(',', ':')).encode(), selection.read_bytes())
        self.assertEqual(sha(selection), expected['selection']['sha256'])
        self.assertEqual(sha(EXAMPLE / 'baseline.yaml'), expected['selection']['eligibilitySha256'])
        self.assertEqual(list(range(1, expected['shardCount'] + 1)), [node['index'] for node in expected['expectedNodes']])

    def test_positive_examples_bind_nodes_and_original_xml_bytes(self):
        expected = read(EXAMPLE / 'expected.json')
        report = read(EXAMPLE / 'report.json')
        self.assertEqual(sha(EXAMPLE / 'expected.json'), report['expectedRequestSha256'])
        totals = dict(tests=0, executed=0, failures=0, errors=0, skipped=0)
        for node, result in zip(expected['expectedNodes'], report['nodes'], strict=True):
            root = EXAMPLE / f"node-{node['index']}"
            evidence = read(root / 'evidence.json')
            self.assertEqual(expected['cohort'], evidence['cohort'])
            self.assertEqual(node['jobId'], evidence['producer']['jobId'])
            self.assertEqual(node['planSha256'], sha(root / 'plan.yaml'))
            self.assertEqual(node['planSha256'], evidence['selection']['planSha256'])
            for key, value in expected['selection'].items():
                self.assertEqual(value, evidence['selection'][key])
            self.assertEqual(node['index'], evidence['shard']['index'])
            self.assertEqual(expected['shardCount'], evidence['shard']['count'])
            self.assertEqual('completed', evidence['process']['state'])
            self.assertEqual(0, evidence['process']['exitCode'])
            observed = dict(tests=0, failures=0, errors=0, skipped=0)
            for descriptor in evidence['reports']:
                path = root / descriptor['path']
                self.assertEqual(sha(path), descriptor['sha256'])
                self.assertEqual(path.stat().st_size, descriptor['bytes'])
                xml = ET.parse(path).getroot()  # Authored bounded fixture only, not an untrusted-report validator.
                for key in observed:
                    observed[key] += int(xml.get(key))
            self.assertEqual(observed, evidence['observed'])
            self.assertEqual(sha(root / 'evidence.json'), result['evidenceSha256'])
            self.assertEqual({**observed, 'executed': observed['tests'] - observed['skipped']}, result['counts'])
            for key in totals:
                totals[key] += result['counts'][key]
        self.assertEqual(totals, report['totals'])
        self.assertTrue(all(gate == 'PASSED' for gate in report['gates'].values()))
        self.assertGreaterEqual(totals['executed'], expected['minimumExecutedTests'])

    def test_missing_shard_example_does_not_claim_complete_counts(self):
        report = read(EXAMPLE / 'report-missing-shard.json')
        self.assertEqual('FAILED', report['status'])
        self.assertIsNone(report['totals'])
        missing = report['nodes'][1]
        self.assertIsNone(missing['evidenceSha256'])
        self.assertIsNone(missing['counts'])
        self.assertEqual(['MISSING_SHARD'], missing['reasons'])
        self.assertEqual('UNKNOWN', report['gates']['nativeProcess'])
        self.assertEqual('FAILED', report['gates']['provenance'])


if __name__ == '__main__':
    unittest.main()
