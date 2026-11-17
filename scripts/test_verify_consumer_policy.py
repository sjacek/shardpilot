"""Acceptance trust checks must precede any Java execution or stale-result reuse."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import verify_consumer_policy as policy
from collect_ci_baseline import sha256


class ConsumerPolicyTrustTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.capture = self.root / 'capture'
        self.capture.mkdir()
        (self.capture / 'baseline.yaml').write_text('schemaVersion: 1\nclasses:\n  - example.OneIT\n')
        (self.capture / 'history.tsv').write_text('example.OneIT\t1\n')
        report = {'status': 'passed', 'revision': 'a' * 40, 'baselineSha256': sha256(self.capture / 'baseline.yaml'),
                  'historySha256': sha256(self.capture / 'history.tsv')}
        (self.capture / 'report.json').write_text(json.dumps(report))

    def test_wrong_checkout_revision_fails_before_runtime_is_read(self):
        with patch.object(policy.subprocess, 'check_output', return_value='b' * 40), patch.object(policy, 'runtime_inventory') as runtime:
            with self.assertRaisesRegex(ValueError, 'revision'):
                policy.verify(self.root, self.capture, self.root / 'policy.yaml', 'unused', 'java', self.root / 'out')
            runtime.assert_not_called()

    def test_changed_baseline_and_history_fail_before_any_tool_runs(self):
        for name in ('baseline.yaml', 'history.tsv'):
            path = self.capture / name
            original = path.read_bytes()
            path.write_bytes(original + b'changed')
            with patch.object(policy.subprocess, 'check_output', return_value='a' * 40), patch.object(policy.subprocess, 'run') as java:
                with self.assertRaisesRegex(ValueError, 'changed'):
                    policy.verify(self.root, self.capture, self.root / 'policy.yaml', 'unused', 'java', self.root / 'out')
                java.assert_not_called()
            path.write_bytes(original)

    def test_old_acceptance_output_is_not_reused(self):
        output = self.root / 'out'
        output.mkdir()
        with patch.object(policy.subprocess, 'check_output', return_value='a' * 40):
            with self.assertRaisesRegex(ValueError, 'fresh'):
                policy.verify(self.root, self.capture, self.root / 'policy.yaml', 'unused', 'java', output)

    def test_runtime_inventory_detects_modified_jar_and_class_files(self):
        jar = self.root / 'runtime.jar'
        jar.write_bytes(b'first-reviewed-testdata')
        original = policy.runtime_inventory(str(jar))
        jar.write_bytes(b'changed-testdata')
        self.assertNotEqual(original, policy.runtime_inventory(str(jar)))
        directory = self.root / 'classes'
        directory.mkdir()
        file = directory / 'Probe.class'
        file.write_bytes(b'first-class-testdata')
        original = policy.runtime_inventory(str(directory))
        file.write_bytes(b'another-class-testdata')
        self.assertNotEqual(original, policy.runtime_inventory(str(directory)))

    def test_missing_and_empty_runtime_entries_are_rejected(self):
        empty = self.root / 'empty'
        empty.mkdir()
        for entry in (self.root / 'missing.jar', empty):
            with self.assertRaises(ValueError):
                policy.runtime_inventory(str(entry))

    def test_inputs_and_runtime_cannot_change_while_core_is_running(self):
        maintained = self.root / 'policy.yaml'
        maintained.write_text('reviewed-policy-testdata')
        jar = self.root / 'core.jar'
        jar.write_bytes(b'reviewed-runtime-testdata')
        for index, changed in enumerate((maintained, jar, self.capture / 'history.tsv')):
            original = changed.read_bytes()
            def run(*_args, **_kwargs):
                changed.write_bytes(original + b'changed-during-evaluation')
                return SimpleNamespace(returncode=0)
            with patch.object(policy.subprocess, 'check_output', side_effect=[
                    'a' * 40, b'src/integrationTest/java/example/OneIT.java\0']), patch.object(policy.subprocess, 'run', side_effect=run):
                with self.assertRaisesRegex(ValueError, 'changed during evaluation'):
                    policy.verify(self.root, self.capture, maintained, str(jar), 'java', self.root / f'out-{index}')
            self.assertFalse((self.root / f'out-{index}/report.json').exists())
            changed.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
