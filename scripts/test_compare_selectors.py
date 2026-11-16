"""Frozen-input integrity, exact review gates and real-core selection replay regressions."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import compare_selectors as comparison
import run_selection_workflow as workflow


# Portable reference behavior only. Actual service source is captured privately by freeze();
# this fixture must not be presented as a vendored or complete service selector.
LEGACY = '''
def select(eligible, paths, core, durations):
    result = {name: ['required-core'] for name in core}
    if core - eligible: raise ValueError('private-error-sentinel')
    for path in paths:
        if path.startswith('docs/'): continue
        if path == 'src/pricing/Price.java':
            for name in ['example.ASlowIT', 'example.ZFastIT']:
                if sum(durations[n] for n in result) + durations[name] <= 60:
                    result[name] = [path]
        elif path == 'src/test/java/example/NightlyIT.java': pass
        else: raise ValueError('private-error-sentinel')
    return {'classes': result}
'''


class SelectorComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.snapshot = self.root / 'snapshot'; self.snapshot.mkdir()
        legacy = self.snapshot / 'legacy'; legacy.mkdir()
        (legacy / 'integration_short.py').write_text(LEGACY)
        (legacy / 'integration_test_durations.py').write_text('')
        (legacy / 'integration-smoke-classes.txt').write_text('example.LoginIT\n')
        (self.snapshot / 'history.tsv').write_text('example.LoginIT\t10\nexample.ASlowIT\t50\nexample.ZFastIT\t5\nexample.NightlyIT\t10\n')
        (self.snapshot / 'eligibility.yaml').write_text('schemaVersion: 1\nclasses:\n' + ''.join(
            '  - example.' + name + 'IT\n' for name in ['Login', 'ASlow', 'ZFast', 'Nightly']))
        (self.snapshot / 'policy.yaml').write_text('''
schemaVersion: 2
profiles:
  daily-testdata:
    mode: affected
    mandatory: {classes: [example.LoginIT]}
    budget: {maxSeconds: 60, unknownSeconds: 30, overflow: fail}
    onUnmatchedChanges: fail
    impact:
      changedTests: [{path: src/test/java}]
    rules:
      - {id: docs, changes: ['docs/**'], action: ignore}
      - {id: pricing, changes: ['src/pricing/**'], action: optional, tests: {classes: [example.ASlowIT, example.ZFastIT]}}
''')
        comparison.write_json(self.snapshot / 'scenarios.json', [
            {'id': 'documentation-testdata', 'paths': ['docs/guide.md']},
            {'id': 'budget-testdata', 'paths': ['src/pricing/Price.java']},
            {'id': 'nightly-testdata', 'paths': ['src/test/java/example/NightlyIT.java']},
            {'id': 'unmapped-testdata', 'paths': ['unknown.txt']}])
        self.seal()

    def seal(self):
        comparison.write_json(self.snapshot / 'inputs.json', {'schemaVersion': 1, 'baseRevision': 'a' * 40,
            'headRevision': 'b' * 40, 'eligibilityKind': 'synthetic-testdata', 'files': {
                file.relative_to(self.snapshot).as_posix(): comparison.digest(file)
                for file in self.snapshot.rglob('*') if file.is_file() and file.name != 'inputs.json'}})

    def test_changed_script_policy_history_and_baseline_fail_before_any_tool_runs(self):
        for name in ['legacy/integration_short.py', 'policy.yaml', 'history.tsv', 'eligibility.yaml', 'scenarios.json']:
            file = self.snapshot / name; old = file.read_bytes(); file.write_bytes(old + b'changed')
            with patch.object(comparison, 'load_legacy') as load:
                with self.assertRaisesRegex(ValueError, 'changed'):
                    comparison.compare(self.snapshot, self.root / 'out', 'unused')
                load.assert_not_called()
            file.write_bytes(old)

    def test_parent_paths_are_rejected_before_import(self):
        data = json.loads((self.snapshot / 'inputs.json').read_text()); data['files']['../escape'] = '0' * 64
        comparison.write_json(self.snapshot / 'inputs.json', data)
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            comparison.verify_snapshot(self.snapshot)

    def test_old_output_is_never_reused_or_written_inside_snapshot(self):
        for output in [self.snapshot / 'out', self.snapshot]:
            with self.assertRaisesRegex(ValueError, 'fresh'):
                comparison.compare(self.snapshot, output, 'unused')

    def test_explanation_requires_exact_sets_statuses_and_nonempty_review(self):
        legacy = {'status': 'PASSED', 'classes': {'example.OldIT': []}}
        decision = {'status': 'PASSED', 'families': [{'disposition': 'SELECTED', 'members': ['example.NewIT']}]}
        result = comparison.explain(legacy, decision, None)
        self.assertEqual('REVIEW_REQUIRED', result['status'])
        review = {k: v for k, v in result.items() if k not in ['status', 'explanation']}
        review['explanation'] = 'Reviewed optional ordering difference.'
        self.assertEqual('EXPLAINED', comparison.explain(legacy, decision, review)['status'])
        for change in [{'shardPilotOnly': []}, {'explanation': ''}, {'legacyStatus': 'FAILED'}]:
            self.assertEqual('REVIEW_REQUIRED', comparison.explain(legacy, decision, {**review, **change})['status'])

    @unittest.skipUnless(os.environ.get('SHARDPILOT_SELECTION_CLASSPATH'), 'Real-core replay runs through the Gradle test fixture')
    def test_real_core_replay_matches_or_requires_exact_review_and_is_deterministic(self):
        classpath = os.environ['SHARDPILOT_SELECTION_CLASSPATH']
        java = os.environ.get('SHARDPILOT_SELECTION_JAVA', 'java')
        first = comparison.compare(self.snapshot, self.root / 'first', classpath, java)
        self.assertEqual(['MATCH', 'REVIEW_REQUIRED', 'REVIEW_REQUIRED', 'MATCH'], [r['status'] for r in first['scenarios']])
        review = {}
        for row in first['scenarios']:
            if row['status'] == 'REVIEW_REQUIRED':
                review[row['id']] = {k: row[k] for k in ['legacyOnly', 'shardPilotOnly', 'legacyStatus', 'shardPilotStatus']}
                review[row['id']]['explanation'] = ('Changed native tests are protected.' if row['id'] == 'nightly-testdata'
                                                   else 'Equal-priority optional families use cost before name, rather than name alone.')
        self.assertEqual(['example.ASlowIT'], review['budget-testdata']['legacyOnly'])
        self.assertEqual(['example.ZFastIT'], review['budget-testdata']['shardPilotOnly'])
        self.assertEqual(['example.NightlyIT'], review['nightly-testdata']['shardPilotOnly'])
        reviewed = self.root / 'review.json'; comparison.write_json(reviewed, review)
        second = comparison.compare(self.snapshot, self.root / 'second', classpath, java, reviewed)
        self.assertEqual('PASSED', second['status'])
        for case in ['documentation-testdata', 'budget-testdata', 'nightly-testdata']:
            for file in ['decision/decision.json', 'plan/planned-1.yaml', 'plan/planned-2.yaml']:
                self.assertEqual((self.root / 'first' / case / 'native' / file).read_bytes(),
                                 (self.root / 'second' / case / 'native' / file).read_bytes())
        self.assertNotIn('private-error-sentinel', json.dumps(second))
        self.assertFalse(second['provesNativeExecution'])

    def test_native_evidence_retains_failed_exit_even_when_xml_is_successful(self):
        evidence = self.root / 'evidence'; evidence.mkdir()
        plan = self.root / 'plan.yaml'; plan.write_text('schemaVersion: 1\nclasses: [example.LoginIT]\n')
        reports = self.root / 'reports'; reports.mkdir()
        (reports / 'TEST-login.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.LoginIT" name="login"/></testsuite>')
        process = {'state': 'completed', 'exitCode': 137, 'startedAt': '2026-11-15T00:00:00Z', 'finishedAt': '2026-11-15T00:00:01Z'}
        workflow.retain_node(evidence, 1, plan, reports, process, {}, {})
        document = json.loads((evidence / 'nodes/node-1/evidence.json').read_text())
        self.assertEqual(137, document['process']['exitCode'])
        self.assertEqual(1, document['observed']['tests'])
        self.assertEqual(hashlib.sha256((reports / 'TEST-login.xml').read_bytes()).hexdigest(), document['reports'][0]['sha256'])

    def test_native_fixture_rejects_dtd_instead_of_expanding_entities(self):
        reports = self.root / 'reports'; reports.mkdir()
        (reports / 'TEST-invalid.xml').write_text('<!DOCTYPE testsuite><testsuite/>')
        plan = self.root / 'plan.yaml'; plan.write_text('fixture')
        with self.assertRaisesRegex(ValueError, 'Invalid native'):
            workflow.retain_node(self.root / 'evidence', 1, plan, reports, {}, {}, {})

    def test_both_examples_share_policy_history_and_native_sources(self):
        left = comparison.ROOT / 'examples/gradle/junit-jupiter/selection-workflow'
        right = comparison.ROOT / 'examples/maven/selection-workflow'
        for file in [left / 'policy.yaml', left / 'durations.tsv', *left.rglob('*.java')]:
            self.assertEqual(file.read_bytes(), (right / file.relative_to(left)).read_bytes())


if __name__ == '__main__':
    unittest.main()
